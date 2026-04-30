output "resource_group_name" {
  description = "Name of the observability resource group."
  value       = azurerm_resource_group.obs.name
}

output "log_analytics_workspace_id" {
  description = "Log Analytics workspace ID — wired into AKS oms_agent."
  value       = azurerm_log_analytics_workspace.main.id
}

output "log_analytics_workspace_name" {
  description = "Log Analytics workspace name."
  value       = azurerm_log_analytics_workspace.main.name
}

output "app_insights_id" {
  description = "Application Insights resource ID."
  value       = azurerm_application_insights.main.id
}

output "app_insights_connection_string" {
  description = "App Insights connection string — set as APPLICATIONINSIGHTS_CONNECTION_STRING env on workloads."
  value       = azurerm_application_insights.main.connection_string
  sensitive   = true
}

output "app_insights_instrumentation_key" {
  description = "App Insights instrumentation key (legacy; prefer connection_string)."
  value       = azurerm_application_insights.main.instrumentation_key
  sensitive   = true
}
