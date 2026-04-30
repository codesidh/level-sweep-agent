# =============================================================================
# Module: AKS cluster.
# Phase 0 — skeleton. Phase 7+ wires the node pools described in
# architecture-spec §16.2 (system / hot / ai / warm / kafka / ops).
# =============================================================================

# Phase 0 placeholder — uncomment when a real cluster is provisioned.
#
# resource "azurerm_resource_group" "aks" {
#   name     = "rg-${var.project}-${var.environment}-aks"
#   location = var.location
#   tags     = var.tags
# }
#
# resource "azurerm_kubernetes_cluster" "main" {
#   name                = "aks-${var.project}-${var.environment}"
#   location            = azurerm_resource_group.aks.location
#   resource_group_name = azurerm_resource_group.aks.name
#   dns_prefix          = "${var.project}-${var.environment}"
#
#   default_node_pool {
#     name       = "system"
#     node_count = 3
#     vm_size    = "Standard_D4s_v5"
#   }
#
#   identity {
#     type = "SystemAssigned"
#   }
#
#   tags = var.tags
# }
