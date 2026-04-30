# =============================================================================
# Module: Key Vault (per-tenant scoped secrets).
# Phase 0 — skeleton; per architecture-spec §3 every tenant gets its own
# encryption key, and Phase A's owner Alpaca token lives here encrypted.
# =============================================================================

# resource "azurerm_resource_group" "kv" {
#   name     = "rg-${var.project}-${var.environment}-kv"
#   location = var.location
#   tags     = var.tags
# }
#
# resource "azurerm_key_vault" "main" {
#   name                       = "kv-${var.project}-${var.environment}"
#   location                   = azurerm_resource_group.kv.location
#   resource_group_name        = azurerm_resource_group.kv.name
#   tenant_id                  = var.azure_tenant_id
#   sku_name                   = "standard"
#   purge_protection_enabled   = true
#   soft_delete_retention_days = 90
#   tags                       = var.tags
# }
