output "resource_group_name" {
  description = "Name of the Key Vault resource group."
  value       = azurerm_resource_group.kv.name
}

output "kv_id" {
  description = "ID of the Key Vault — used for role assignments + private endpoints."
  value       = azurerm_key_vault.main.id
}

output "kv_name" {
  description = "Name of the Key Vault."
  value       = azurerm_key_vault.main.name
}

output "kv_uri" {
  description = "Vault URI (https://<name>.vault.azure.net/) — used by CSI driver SecretProviderClass."
  value       = azurerm_key_vault.main.vault_uri
}
