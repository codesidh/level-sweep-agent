# =============================================================================
# Environment: dev
# Phase 1 target — provisions AKS + ACR + Key Vault + App Insights / Log
# Analytics + VNET / NAT Gateway + GHA federated identities for OIDC.
# =============================================================================

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Backend wired post-bootstrap. Until the bootstrap state container exists,
  # `terraform init -backend=false` works for validate.
  # backend "azurerm" {
  #   resource_group_name  = "rg-levelsweep-tfstate"
  #   storage_account_name = "stlevelsweeptfstate"
  #   container_name       = "tfstate"
  #   key                  = "dev/levelsweep.tfstate"
  #   use_oidc             = true
  # }
}

provider "azurerm" {
  features {}
  use_oidc = true
}

provider "random" {}

locals {
  environment = "dev"
  tags = merge(
    {
      project     = var.project
      environment = local.environment
      managed-by  = "terraform"
      tier        = "non-prod"
    },
    var.tags
  )
}

# -----------------------------------------------------------------------------
# Module composition. Outputs are passed explicitly to keep dependencies
# legible (no implicit module-to-module references).
# -----------------------------------------------------------------------------

module "networking" {
  source      = "../../modules/networking"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
}

module "registry" {
  source      = "../../modules/registry"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
}

module "observability" {
  source      = "../../modules/observability"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
  alert_email = var.alert_email
}

module "keyvault" {
  source          = "../../modules/keyvault"
  project         = var.project
  environment     = local.environment
  location        = var.location
  azure_tenant_id = var.tenant_id
  tags            = local.tags
}

module "aks" {
  source                     = "../../modules/aks"
  project                    = var.project
  environment                = local.environment
  location                   = var.location
  tags                       = local.tags
  subnet_aks_id              = module.networking.subnet_aks_id
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  acr_id                     = module.registry.acr_id

  # Phase 1 dev cost-tuned defaults — see `architecture-spec.md` §16 cost table.
  # Production (Phase 7) overrides these in `infra/environments/prod/main.tf`
  # for HA + sustained throughput.
  #
  # vm_size: Standard_D2s_v4 (2 vCPU, 8 GB, amd64, ~$70/mo). Cheapest amd64
  # option allowed by this subscription's quota in eastus — B-series amd64
  # (cheaper, ~$60/mo) and Dsv5 series are not included in the allowed list
  # for new Pay-As-You-Go subscriptions. ARM-based B-series (b2ps_v2) is
  # allowed but would require multi-arch container builds. Phase 7 may
  # request quota extension for B-series amd64 to drop cost further.
  node_count          = 1
  vm_size             = "Standard_D2s_v4"
  enable_azure_policy = false
  outbound_type       = "loadBalancer"

  depends_on = [module.networking]
}

module "github_oidc" {
  source                = "../../modules/github_oidc"
  project               = var.project
  environment           = local.environment
  location              = var.location
  tags                  = local.tags
  aks_resource_group_id = module.aks.cluster_resource_group_id
  acr_id                = module.registry.acr_id
  key_vault_id          = module.keyvault.kv_id
}

# -----------------------------------------------------------------------------
# Workload identity for the market-data-service pod's KV CSI mount.
# Phase 1 reuses the AKS kubelet managed identity as the workload identity —
# the Helm chart sets `azure.workload.identity/client-id` on the SA to
# `kubelet_client_id`. To make that work we need:
#   1) a federated credential binding the kubelet MI to the SA's OIDC subject
#   2) `Key Vault Secrets User` on the KV scoped to the kubelet MI
# Phase 7 splits this into a dedicated per-service workload MI.
# -----------------------------------------------------------------------------

resource "azurerm_federated_identity_credential" "market_data_sa" {
  name                = "fc-${var.project}-${local.environment}-market-data-service"
  resource_group_name = module.aks.cluster_resource_group
  parent_id           = module.aks.kubelet_user_assigned_identity_id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = module.aks.oidc_issuer_url
  subject             = "system:serviceaccount:market-data:market-data-service"
}

resource "azurerm_role_assignment" "kubelet_kv_secrets_user" {
  scope                = module.keyvault.kv_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = module.aks.kubelet_object_id
}

# -----------------------------------------------------------------------------
# Variables (root vars module are duplicated here so each env stands alone
# under the iac.yml matrix).
# -----------------------------------------------------------------------------

variable "project" {
  description = "Project short name."
  type        = string
  default     = "levelsweep"
}

variable "location" {
  description = "Azure region."
  type        = string
  default     = "eastus"
}

variable "tenant_id" {
  description = "Azure AD tenant id."
  type        = string
  default     = ""
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}

variable "alert_email" {
  description = "Email address that the Phase 1 alerts page when they fire. Set in terraform.tfvars (out of source control) — Phase 7 swaps in a Twilio webhook for SMS escalation."
  type        = string
  default     = ""
}

# -----------------------------------------------------------------------------
# Outputs — re-exported for the deploy workflow + operator post-apply steps.
# -----------------------------------------------------------------------------

output "environment" {
  description = "Environment name."
  value       = local.environment
}

output "aks_cluster_name" {
  description = "AKS cluster name — pass to az aks get-credentials."
  value       = module.aks.cluster_name
}

output "aks_resource_group" {
  description = "AKS resource group."
  value       = module.aks.cluster_resource_group
}

output "aks_kubelet_client_id" {
  description = "Client ID of the AKS kubelet managed identity — set as the AKS_KUBELET_CLIENT_ID GitHub secret. Phase 1 reuses this for the Key Vault CSI / workload-identity binding; Phase 7 swaps in a dedicated per-service MI."
  value       = module.aks.kubelet_client_id
}

output "acr_login_server" {
  description = "ACR login server FQDN."
  value       = module.registry.acr_login_server
}

output "acr_name" {
  description = "ACR registry name."
  value       = module.registry.acr_name
}

output "key_vault_name" {
  description = "Key Vault name (for `az keyvault secret set --vault-name ...`)."
  value       = module.keyvault.kv_name
}

output "key_vault_uri" {
  description = "Key Vault URI."
  value       = module.keyvault.kv_uri
}

output "app_insights_connection_string" {
  description = "App Insights connection string."
  value       = module.observability.app_insights_connection_string
  sensitive   = true
}

output "log_analytics_workspace_id" {
  description = "Log Analytics workspace ID."
  value       = module.observability.log_analytics_workspace_id
}

output "nat_egress_ip" {
  description = "Static egress IP — register on Alpaca / Anthropic / Trading Economics allowlists."
  value       = module.networking.nat_egress_ip
}

output "gha_client_id" {
  description = "GHA federated identity client ID — set as the AZURE_CLIENT_ID GitHub secret."
  value       = module.github_oidc.client_id
}

output "tenant_id" {
  description = "Azure AD tenant ID — set as the AZURE_TENANT_ID GitHub secret."
  value       = module.github_oidc.tenant_id
}
