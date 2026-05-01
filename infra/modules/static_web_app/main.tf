# =============================================================================
# Module: static_web_app
# Hosts the architecture diagrams (and any future static docs) on Azure Static
# Web Apps Free tier. URL is `*.<region>.azurestaticapps.net`; the apex
# `documentation.<region>.azurestaticapps.net`-style domain is fine for Phase 1.
#
# Free tier: $0/month, 100 GB/month bandwidth, 0.5 GB storage, 2 custom
# domains, free TLS. No SLA.
# =============================================================================

resource "azurerm_resource_group" "swa" {
  name     = "rg-${var.project}-${var.environment}-swa"
  location = var.location
  tags     = var.tags
}

resource "azurerm_static_web_app" "main" {
  name                = "swa-${var.project}-${var.environment}-docs"
  resource_group_name = azurerm_resource_group.swa.name
  # Static Web Apps Free tier is region-restricted; eastus2 is the closest
  # supported region to eastus. The compute region only affects the deploy
  # workers — content is globally CDN-served.
  location = var.swa_location

  sku_tier = "Free"
  sku_size = "Free"

  tags = var.tags
}
