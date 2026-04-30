# =============================================================================
# Module: networking (VNET, subnets, NAT gateway, private endpoints).
# Phase 0 — skeleton; per architecture-spec §16.3 we need:
#   - Private VNET for AKS
#   - Adjacent VNET for APIM with peering
#   - NAT Gateway with deterministic egress IPs (for Alpaca allowlist)
#   - Private endpoints for MS SQL, Mongo, Key Vault
# =============================================================================

# resource "azurerm_resource_group" "net" {
#   name     = "rg-${var.project}-${var.environment}-net"
#   location = var.location
#   tags     = var.tags
# }
#
# resource "azurerm_virtual_network" "aks" { ... }
# resource "azurerm_nat_gateway" "egress" { ... }
