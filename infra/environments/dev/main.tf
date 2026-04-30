# =============================================================================
# Environment: dev
# Phase 0 — composes the empty modules. Real resources land in Phase 7+.
# =============================================================================

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  # Backend wired in Phase 7+. For Phase 0 use local state during validate.
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

module "networking" {
  source      = "../../modules/networking"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
}

module "keyvault" {
  source          = "../../modules/keyvault"
  project         = var.project
  environment     = local.environment
  location        = var.location
  azure_tenant_id = var.tenant_id
  tags            = local.tags
}

module "storage" {
  source      = "../../modules/storage"
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
}

module "aks" {
  source      = "../../modules/aks"
  project     = var.project
  environment = local.environment
  location    = var.location
  tags        = local.tags
}

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

output "environment" {
  value = local.environment
}
