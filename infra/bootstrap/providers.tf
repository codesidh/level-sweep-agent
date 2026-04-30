# Bootstrap module: provisions the Azure storage backing for Terraform remote state.
# Runs with a LOCAL backend (no chicken-and-egg). After successful apply, the
# outputs feed the `azurerm` backend block in ../providers.tf.

terraform {
  required_version = ">= 1.7"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  # Intentionally LOCAL backend. State for this module lives in
  # bootstrap/terraform.tfstate and SHOULD be committed to a separate
  # secrets-free location or kept on the operator's machine. It is
  # non-sensitive (only resource IDs).
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}
