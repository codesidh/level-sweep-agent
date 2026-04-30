output "resource_group_name" {
  description = "Name of the GHA identity resource group."
  value       = azurerm_resource_group.gha.name
}

output "identity_id" {
  description = "Resource ID of the user-assigned managed identity."
  value       = azurerm_user_assigned_identity.gha.id
}

output "client_id" {
  description = "Client ID — set as the GitHub secret AZURE_CLIENT_ID for the azure/login@v2 action."
  value       = azurerm_user_assigned_identity.gha.client_id
}

output "principal_id" {
  description = "Principal (object) ID of the GHA identity — used for any additional role assignments."
  value       = azurerm_user_assigned_identity.gha.principal_id
}

output "tenant_id" {
  description = "Tenant ID — set as the GitHub secret AZURE_TENANT_ID."
  value       = azurerm_user_assigned_identity.gha.tenant_id
}
