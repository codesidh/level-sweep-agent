# =============================================================================
# Module: observability (App Insights + Log Analytics).
# Workspace-based App Insights — recommended pattern as of azurerm 4.x.
# Phase 1 retention is 30 days for cost; Phase 7 raises to 90+ for SOC 2.
# Per architecture-spec §18 the alert rules table lands in a follow-up PR.
# =============================================================================

resource "azurerm_resource_group" "obs" {
  name     = "rg-${var.project}-${var.environment}-obs"
  location = var.location
  tags     = var.tags
}

resource "azurerm_log_analytics_workspace" "main" {
  name                = "law-${var.project}-${var.environment}"
  location            = azurerm_resource_group.obs.location
  resource_group_name = azurerm_resource_group.obs.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = var.tags
}

resource "azurerm_application_insights" "main" {
  name                = "ai-${var.project}-${var.environment}"
  location            = azurerm_resource_group.obs.location
  resource_group_name = azurerm_resource_group.obs.name
  workspace_id        = azurerm_log_analytics_workspace.main.id

  # Quarkus uses OpenTelemetry; App Insights labels traces by app type.
  # "java" is correct for JVM workloads regardless of the OTel collector path.
  application_type = "java"

  tags = var.tags
}
