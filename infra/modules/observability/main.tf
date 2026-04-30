# =============================================================================
# Module: observability (App Insights + Log Analytics + alert rules).
# Phase 0 — skeleton; per architecture-spec §18 the alert table lists the
# specific triggers that must wire to SMS / email channels.
# =============================================================================

# resource "azurerm_resource_group" "obs" {
#   name     = "rg-${var.project}-${var.environment}-obs"
#   location = var.location
#   tags     = var.tags
# }
#
# resource "azurerm_log_analytics_workspace" "main" { ... }
# resource "azurerm_application_insights" "main"   { ... }
