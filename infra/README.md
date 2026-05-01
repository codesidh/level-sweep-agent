# Infrastructure (Terraform)

Azure infrastructure for LevelSweepAgent. Authoritative architecture lives in
`docs/architecture/`. This README is operational: how to provision and what
the layout means.

## Layout

```
infra/
├── bootstrap/             # one-time tfstate backend (RG + storage account)
├── environments/
│   ├── dev/               # Phase 1 target — fully wired
│   ├── stage/             # dormant per architecture-spec v2.3 §21
│   └── prod/              # Phase 7+ target
├── modules/
│   ├── aks/               # AKS cluster + AcrPull binding
│   ├── github_oidc/       # GHA federated managed identity + role assignments
│   ├── keyvault/          # Key Vault + placeholder secrets
│   ├── networking/        # VNET, subnets, NAT Gateway with static egress IP
│   ├── observability/     # Log Analytics workspace + Application Insights
│   ├── registry/          # Azure Container Registry (Basic SKU)
│   └── storage/           # Phase 0 placeholder; managed data services in Phase 7
├── main.tf, providers.tf, variables.tf, outputs.tf  # shared root composer
```

## Provisioning the dev environment

This is a one-time, operator-run flow. CI does not apply — only validates.

1. `cd infra/environments/dev`
2. `cp terraform.tfvars.example terraform.tfvars` and fill in `tenant_id`
   (find it via `az account show --query tenantId -o tsv`).
3. `az login` (must have `Owner` on the target subscription).
4. `terraform init`
5. `terraform plan -out=tfplan && terraform apply tfplan`
6. Post-apply (one-time, out of band — secret values must not live in TF):
   ```bash
   KV_NAME=$(terraform output -raw key_vault_name)
   az keyvault secret set --vault-name "$KV_NAME" --name alpaca-api-key       --value "$ALPACA_API_KEY"
   az keyvault secret set --vault-name "$KV_NAME" --name alpaca-secret-key    --value "$ALPACA_SECRET_KEY"
   az keyvault secret set --vault-name "$KV_NAME" --name anthropic-api-key    --value "$ANTHROPIC_API_KEY"
   az keyvault secret set --vault-name "$KV_NAME" --name trading-economics-api-key --value "$TRADING_ECONOMICS_API_KEY"
   ```
7. Configure GitHub **repo** secrets (Settings → Secrets and variables → Actions),
   used by `main.yml` for ACR push:
   - `AZURE_CLIENT_ID`       = `terraform output -raw gha_client_id`
   - `AZURE_TENANT_ID`       = `terraform output -raw tenant_id`
   - `AZURE_SUBSCRIPTION_ID` = `az account show --query id -o tsv`
   - `ACR_LOGIN_SERVER`      = `terraform output -raw acr_login_server`
   - `ACR_NAME`              = `terraform output -raw acr_name`

   And configure the GitHub **environment** `dev` (Settings → Environments → New
   environment → `dev`) with the secrets `deploy-dev.yml` consumes:

   | Secret                                  | Source                                                          |
   | --------------------------------------- | --------------------------------------------------------------- |
   | `AZURE_CLIENT_ID`                       | `terraform output -raw gha_client_id`                           |
   | `AZURE_TENANT_ID`                       | `terraform output -raw tenant_id`                               |
   | `AZURE_SUBSCRIPTION_ID`                 | `az account show --query id -o tsv`                             |
   | `AKS_CLUSTER_NAME`                      | `terraform output -raw aks_cluster_name`                        |
   | `AKS_RESOURCE_GROUP`                    | `terraform output -raw aks_resource_group`                      |
   | `AKS_KUBELET_CLIENT_ID`                 | `terraform output -raw aks_kubelet_client_id`                   |
   | `ACR_LOGIN_SERVER`                      | `terraform output -raw acr_login_server`                        |
   | `KEY_VAULT_NAME`                        | `terraform output -raw key_vault_name`                          |
   | `KEY_VAULT_URI`                         | `terraform output -raw key_vault_uri`                           |
   | `APPLICATIONINSIGHTS_CONNECTION_STRING` | `terraform output -raw app_insights_connection_string`          |

   Optional environment **variable** (not a secret):
   - `AZURE_REGION` — defaults to `eastus` if unset; controls the App Insights
     ingest endpoint OTel exporters use.
8. Egress IP allowlisting — Phase 1 dev runs without a NAT Gateway (`nat_egress_ip` returns `null`), so AKS egress comes from the cluster's load balancer SNAT pool. None of Alpaca / Anthropic / Trading Economics require allowlisting at the tier we use, so this is fine for the soak. If a partner upgrade ever requires a deterministic egress IP, follow the NAT-enable steps in the cost section above.

## What gets provisioned (dev — cost-tuned)

| Resource group                       | Contents                                                                  |
| ------------------------------------ | ------------------------------------------------------------------------- |
| `rg-levelsweep-dev-net`              | VNET (10.42.0.0/16), 3 subnets. NAT Gateway disabled by default in dev.   |
| `rg-levelsweep-dev-acr`              | ACR (Basic SKU, admin disabled)                                           |
| `rg-levelsweep-dev-obs`              | Log Analytics workspace (PerGB2018, 30d), App Insights                    |
| `rg-levelsweep-dev-kv`               | Key Vault (RBAC, soft-delete, 4 placeholder secrets)                      |
| `rg-levelsweep-dev-aks`              | AKS cluster (1.30, 1× Standard_B2ms, OIDC + workload identity, LB egress) |
| `rg-levelsweep-dev-gha`              | User-assigned MI + 3 federated creds + scoped roles                       |

### Estimated monthly cost (Phase 1 dev)

~$75–85 / month, dominated by:
- AKS Free tier control plane: $0
- 1× Standard_B2ms node (2 vCPU burstable, 8 GB): ~$60
- ACR Basic: ~$5
- Log Analytics + App Insights at light ingest: ~$10–20

Phase 7 production overrides bump this for HA + sustained throughput. The
cost-tuned values are set in `environments/dev/main.tf` (`node_count`,
`vm_size`, `enable_azure_policy`, `outbound_type`); each is a module variable
so prod can opt into the heavier config.

If a partner ever requires a deterministic egress IP (Alpaca enterprise,
Trading Economics paid tier, etc.), enable the NAT Gateway by setting
`enable_nat_gateway = true` on the `networking` module and switching the AKS
`outbound_type` to `userAssignedNATGateway` — adds ~$35/mo.

## What's deferred to Phase 7

- AKS multi-pool topology (system / hot / ai / warm / kafka / ops per
  architecture-spec §16.2)
- Key Vault network ACLs tightened to `Deny` + private endpoint on snet-pe
- Log Analytics retention raised to 90 days (SOC 2)
- ACR upgraded to Premium SKU + geo-replication + content trust
- VNET peering with APIM VNET; private endpoints for MS SQL / Mongo / KV
- Managed data services: Azure SQL Database, Cosmos Mongo API
  (Phase 1 uses local docker-compose — see `docs/local-dev.md`)

## CI

`.github/workflows/iac.yml` runs `terraform fmt -check`, `terraform init
-backend=false`, and `terraform validate` against `environments/dev` and
`environments/prod` on every PR touching `infra/**`. The `apply` job is
gated behind a manual `workflow_dispatch` trigger and a GitHub Environment
with required reviewers.

## Bootstrap (state backend)

The `bootstrap/` directory has its own [README](./bootstrap/README.md)
covering how to provision the state storage account once per environment.
The dev backend block in `environments/dev/main.tf` is currently
commented-out — flip it on after running bootstrap and migrate state with
`terraform init -migrate-state`.
