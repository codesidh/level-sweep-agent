# =============================================================================
# Provider configuration shared across all environments.
# Phase 0 — backend block commented; flip on once an Azure Storage Account
# for state has been provisioned manually (chicken-and-egg).
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

  # Uncomment once the bootstrap storage account exists:
  # backend "azurerm" {
  #   resource_group_name  = "rg-levelsweep-tfstate"
  #   storage_account_name = "stlevelsweeptfstate"
  #   container_name       = "tfstate"
  #   key                  = "levelsweep.tfstate"
  #   use_oidc             = true
  # }
}

provider "azurerm" {
  features {}
  # Auth via OIDC from GitHub Actions; no static credentials.
  use_oidc = true
}
