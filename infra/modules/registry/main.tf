# =============================================================================
# Module: registry (Azure Container Registry).
# Phase 1 dev-grade: Basic SKU, single replication, admin disabled.
# Phase 7+ steps up to Premium SKU with geo-replication + content trust +
# private endpoint per architecture-spec §16.4.
# =============================================================================

resource "azurerm_resource_group" "acr" {
  name     = "rg-${var.project}-${var.environment}-acr"
  location = var.location
  tags     = var.tags
}

resource "azurerm_container_registry" "main" {
  # ACR names: globally unique, alphanumeric only (no hyphens), 5-50 chars.
  name                = "acr${var.project}${var.environment}"
  resource_group_name = azurerm_resource_group.acr.name
  location            = azurerm_resource_group.acr.location
  sku                 = "Basic"
  admin_enabled       = false
  tags                = var.tags
}
