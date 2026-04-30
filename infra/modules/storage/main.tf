# =============================================================================
# Module: storage (MS SQL + Cosmos Mongo API + general-purpose blob).
# Phase 0 — skeleton; provisioning lands in Phase 7+.
# Per architecture-spec §13: MS SQL is system of record, Mongo holds audit
# + read models + agent memory.
# =============================================================================

# Placeholder — uncomment + parameterize as the data layer is provisioned.
#
# resource "azurerm_resource_group" "data" {
#   name     = "rg-${var.project}-${var.environment}-data"
#   location = var.location
#   tags     = var.tags
# }
#
# resource "azurerm_mssql_server" "primary" { ... }
# resource "azurerm_mssql_database" "level_sweep" { ... }
# resource "azurerm_cosmosdb_account" "mongo" { kind = "MongoDB" ... }
