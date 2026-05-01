output "default_host_name" {
  description = "Auto-generated hostname (`*.azurestaticapps.net`). Public URL for the deployed docs."
  value       = azurerm_static_web_app.main.default_host_name
}

output "name" {
  description = "Static Web App resource name."
  value       = azurerm_static_web_app.main.name
}

output "id" {
  description = "Static Web App resource ID."
  value       = azurerm_static_web_app.main.id
}

output "api_key" {
  description = "Deployment API key — used as the `azure_static_web_apps_api_token` input on `azure/static-web-apps-deploy@v1`. Treat as sensitive."
  value       = azurerm_static_web_app.main.api_key
  sensitive   = true
}
