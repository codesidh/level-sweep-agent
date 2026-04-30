# Terraform State Bootstrap

This module solves the chicken-and-egg of Terraform remote state: the
`azurerm` backend needs a storage account to write state to, but you'd
normally use Terraform to provision that storage account.

The bootstrap module runs **once per environment with a local backend**,
provisions the resource group + storage account + container that will
host all subsequent Terraform state, then prints the backend block to
copy into `../providers.tf`.

## When to run

- Once per environment (`dev`, `stage`, `prod`) at project setup.
- Whenever you spin up a brand-new environment.
- **Not** as part of CI. This module is operator-run on a workstation
  with subscription-level Owner permissions.

## Prerequisites

- Azure CLI logged in: `az login` (and `az account set --subscription <id>`)
- Terraform ≥ 1.7 on PATH
- Operator has `Owner` or `User Access Administrator` on the subscription
  (to grant the `Storage Blob Data Contributor` role)

## How to run

```bash
cd infra/bootstrap

cat > terraform.tfvars <<EOF
subscription_id  = "<your-subscription-id>"
environment      = "dev"
admin_object_ids = ["<your-aad-object-id>"]
EOF

terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

When apply completes, capture the output:

```bash
terraform output -raw backend_block
```

Copy that block into `../providers.tf` inside the `terraform { ... }`
block, then in the parent module run:

```bash
cd ..
terraform init -migrate-state
```

…to migrate the (empty) local state to the new Azure backend.

## State of the bootstrap itself

The bootstrap module's own `terraform.tfstate` lives locally. It contains
**only resource IDs, no secrets**, but you should still keep it. Options:

1. Commit it to a private branch / separate repo (manual hygiene).
2. Keep it on the operator's workstation and document the location.
3. After bootstrap success, migrate this module's state into the very
   container it just created (chicken eats its own egg). Most teams do
   not bother — the bootstrap rarely changes after first apply.

## Re-running

Re-running with the same vars is a no-op (Terraform sees the resources
already exist). Changing `var.project` or `var.environment` after the
first apply will rename resources and **destroy the existing tfstate** —
do not do that.

## Permissions inside the new account

`shared_access_key_enabled = false` — only AAD identities listed in
`admin_object_ids` (and federated CI identities you grant explicitly) can
read/write state. This is pre-aligned with Phase B SOC 2 expectations
(no shared keys, identity-based access only).
