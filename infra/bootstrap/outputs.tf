output "resource_group_name" {
  description = "Resource group containing the tfstate storage account."
  value       = azurerm_resource_group.tfstate.name
}

output "storage_account_name" {
  description = "Name of the tfstate storage account. Paste into ../providers.tf backend block."
  value       = azurerm_storage_account.tfstate.name
}

output "container_name" {
  description = "Blob container that stores Terraform state files."
  value       = azurerm_storage_container.tfstate.name
}

output "backend_block" {
  description = "Copy-paste this block into ../providers.tf (terraform { backend \"azurerm\" { ... } })."
  value       = <<-EOT
    backend "azurerm" {
      resource_group_name  = "${azurerm_resource_group.tfstate.name}"
      storage_account_name = "${azurerm_storage_account.tfstate.name}"
      container_name       = "${azurerm_storage_container.tfstate.name}"
      key                  = "${var.environment}.tfstate"
      use_azuread_auth     = true
    }
  EOT
}
