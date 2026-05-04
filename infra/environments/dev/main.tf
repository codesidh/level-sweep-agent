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

  # Phase 3 dev cost-tuned defaults — see `architecture-spec.md` §16 cost table.
  # Production (Phase 7) overrides these in `infra/environments/prod/main.tf`
  # for HA + sustained throughput.
  #
  # vm_size: Standard_D2s_v4 (2 vCPU, 8 GB, amd64, ~$70/mo). Cheapest amd64
  # option allowed by this subscription's quota in eastus — B-series amd64
  # (cheaper, ~$60/mo) and Dsv5 series are not included in the allowed list
  # for new Pay-As-You-Go subscriptions. ARM-based B-series (b2ps_v2) is
  # allowed but would require multi-arch container builds. Phase 7 may
  # request quota extension for B-series amd64 to drop cost further.
  #
  # node_count: bumped from 1 → 2 in Phase 3 because the dev cluster now hosts
  # both market-data-service and execution-service concurrently. Each pod
  # requests 250m CPU / 768 MiB; with DaemonSets + system overhead a single
  # D2s_v4 node has < 500m CPU available, which forces one of the two
  # workloads into Pending. Two nodes give each pod its own scheduling
  # headroom (~$140/mo total: 2 × ~$70/mo). When `iac.yml` runs
  # `terraform apply` on this change AKS performs a node-pool upgrade — the
  # existing market-data-service pod is rescheduled onto the new node pool
  # via a rolling roll; no traffic loss is expected for Phase 1 services
  # (market-data-service has no inbound user traffic, only an outbound
  # WebSocket to Alpaca which auto-reconnects). Phase 7 revisits HPA + a
  # 3-node pool once additional services land.
  node_count          = 2
  vm_size             = "Standard_D2s_v4"
  enable_azure_policy = false
  outbound_type       = "loadBalancer"

  depends_on = [module.networking]
}

# -----------------------------------------------------------------------------
# Worker node pool — added when Phase 6 services start landing (Journal,
# User-Config, Projection, Calendar, Notification, API Gateway, Strimzi
# Kafka brokers). The 2-node system pool above (4 vCPU / 16 GiB total) is
# already running Phase 1-4 services (market-data-service + execution-service
# + ai-agent-service); Phase 6+ would push it past capacity. The worker
# pool adds 2 × D2s_v4 (4 vCPU / 16 GiB total = doubles capacity, ~$140/mo).
#
# No taint — workloads schedule on either pool by capacity. When Phase 7
# splits per-workload pools (system / hot / ai / warm / kafka per
# architecture-spec §16.2), introduce taints + nodeSelectors then.
# -----------------------------------------------------------------------------

resource "azurerm_kubernetes_cluster_node_pool" "worker" {
  name                  = "worker"
  kubernetes_cluster_id = module.aks.cluster_id
  vm_size               = "Standard_D2s_v4"
  node_count            = 2
  vnet_subnet_id        = module.networking.subnet_aks_id
  mode                  = "User"
  os_type               = "Linux"
  os_disk_size_gb       = 30
  tags                  = local.tags
}

module "static_web_app" {
  source      = "../../modules/static_web_app"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
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

# Phase 3: execution-service binds to the same kubelet MI via its own federated
# credential. Subject must match the deploy-dev.yml `--namespace` +
# ServiceAccount name produced by the Helm chart. Phase 7 splits this off into
# a dedicated per-service workload MI alongside market-data-service.
resource "azurerm_federated_identity_credential" "execution_service_sa" {
  name                = "fc-${var.project}-${local.environment}-execution-service"
  resource_group_name = module.aks.cluster_resource_group
  parent_id           = module.aks.kubelet_user_assigned_identity_id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = module.aks.oidc_issuer_url
  subject             = "system:serviceaccount:execution-service:execution-service"
}

# Phase 4: ai-agent-service binds to the same kubelet MI via its own federated
# credential. Subject must match the deploy-dev.yml `--namespace` +
# ServiceAccount name produced by the Helm chart. The dev cluster's 2 ×
# Standard_D2s_v4 (4 vCPU / 16 GiB total) capacity, sized in Phase 3 for
# market-data-service + execution-service, has the headroom to host this
# third Quarkus pod (each requests 250m CPU / 768 MiB → 3 × ~1 GiB ~= 3 GiB
# of 16 GiB; well within budget). Phase 7 splits this off into a dedicated
# per-service workload MI alongside market-data-service + execution-service
# AND revisits the node-pool size once decision-engine and the cold-path
# Spring services land.
resource "azurerm_federated_identity_credential" "ai_agent_service_sa" {
  name                = "fc-${var.project}-${local.environment}-ai-agent-service"
  resource_group_name = module.aks.cluster_resource_group
  parent_id           = module.aks.kubelet_user_assigned_identity_id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = module.aks.oidc_issuer_url
  subject             = "system:serviceaccount:ai-agent:ai-agent-service"
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

output "static_web_app_host_name" {
  description = "Public URL host for the architecture-docs Static Web App."
  value       = module.static_web_app.default_host_name
}

output "static_web_app_api_key" {
  description = "Deployment token for the architecture-docs Static Web App. Set as the AZURE_STATIC_WEB_APPS_API_TOKEN repo secret."
  value       = module.static_web_app.api_key
  sensitive   = true
}
