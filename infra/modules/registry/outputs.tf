output "resource_group_name" {
  description = "Name of the ACR resource group."
  value       = azurerm_resource_group.acr.name
}

output "acr_id" {
  description = "Resource ID of the ACR — used for AKS AcrPull / GHA AcrPush role assignments."
  value       = azurerm_container_registry.main.id
}

output "acr_name" {
  description = "ACR name (without the .azurecr.io suffix)."
  value       = azurerm_container_registry.main.name
}

output "acr_login_server" {
  description = "ACR login server FQDN — passed to docker login + Helm imagePullSecrets."
  value       = azurerm_container_registry.main.login_server
}
