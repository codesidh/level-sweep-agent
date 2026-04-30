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
7. Configure GitHub repo secrets (Settings → Secrets and variables → Actions):
   - `AZURE_CLIENT_ID`       = `terraform output -raw gha_client_id`
   - `AZURE_TENANT_ID`       = `terraform output -raw tenant_id`
   - `AZURE_SUBSCRIPTION_ID` = `az account show --query id -o tsv`
8. Add the egress IP to external service allowlists:
   ```bash
   terraform output -raw nat_egress_ip
   ```
   Register on:
   - Alpaca dashboard: https://alpaca.markets/dashboard (API key IP allowlist)
   - Trading Economics: https://tradingeconomics.com/api (account → security)
   - Anthropic does not require IP allowlisting in standard plans.

## What gets provisioned (dev)

| Resource group                       | Contents                                                |
| ------------------------------------ | ------------------------------------------------------- |
| `rg-levelsweep-dev-net`              | VNET (10.42.0.0/16), 3 subnets, NAT Gateway, public IP  |
| `rg-levelsweep-dev-acr`              | ACR (Basic SKU, admin disabled)                         |
| `rg-levelsweep-dev-obs`              | Log Analytics workspace (PerGB2018, 30d), App Insights  |
| `rg-levelsweep-dev-kv`               | Key Vault (RBAC, soft-delete, 4 placeholder secrets)    |
| `rg-levelsweep-dev-aks`              | AKS cluster (1.30, 2x D4s_v5, OIDC + workload identity) |
| `rg-levelsweep-dev-gha`              | User-assigned MI + 3 federated creds + scoped roles     |

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
