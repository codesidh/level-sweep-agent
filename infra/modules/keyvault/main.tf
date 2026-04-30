# =============================================================================
# Module: Key Vault (per-tenant scoped secrets).
# RBAC-only authorization; Phase B SOC 2 alignment (no access policies).
# Phase 1 keeps network ACLs open ("Allow") to simplify dev; Phase 7 tightens
# with `default_action = "Deny"` + private endpoint attached to snet-pe.
# =============================================================================

data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 6
  upper   = false
  lower   = true
  numeric = true
  special = false
}

resource "azurerm_resource_group" "kv" {
  name     = "rg-${var.project}-${var.environment}-kv"
  location = var.location
  tags     = var.tags
}

resource "azurerm_key_vault" "main" {
  # KV names are globally unique; suffix keeps re-creates collision-free.
  name                = "kv-${var.project}-${var.environment}-${random_string.suffix.result}"
  location            = azurerm_resource_group.kv.location
  resource_group_name = azurerm_resource_group.kv.name
  tenant_id           = var.azure_tenant_id
  sku_name            = "standard"

  rbac_authorization_enabled = true
  purge_protection_enabled   = true
  soft_delete_retention_days = 30

  network_acls {
    default_action = "Allow"
    bypass         = "AzureServices"
  }

  tags = var.tags
}

# Grant the identity that runs `terraform apply` permission to create secrets.
# Required because the placeholder `azurerm_key_vault_secret` resources below
# need to be writeable on first apply.
resource "azurerm_role_assignment" "kv_admin_for_runner" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# -----------------------------------------------------------------------------
# Placeholder secrets — actual values are populated out-of-band via:
#   az keyvault secret set --vault-name <kv> --name <name> --value <value>
# Listed here so the deploy workflow's CSI mount knows which secret names to
# expect, and so destroy/replace cycles don't silently drop them.
# `lifecycle { ignore_changes = [value] }` prevents `apply` from clobbering
# operator-set values on subsequent runs.
# -----------------------------------------------------------------------------

resource "azurerm_key_vault_secret" "alpaca_api_key" {
  name         = "alpaca-api-key"
  value        = "REPLACE_AFTER_APPLY"
  key_vault_id = azurerm_key_vault.main.id

  lifecycle {
    ignore_changes = [value]
  }

  depends_on = [azurerm_role_assignment.kv_admin_for_runner]
}

resource "azurerm_key_vault_secret" "alpaca_secret_key" {
  name         = "alpaca-secret-key"
  value        = "REPLACE_AFTER_APPLY"
  key_vault_id = azurerm_key_vault.main.id

  lifecycle {
    ignore_changes = [value]
  }

  depends_on = [azurerm_role_assignment.kv_admin_for_runner]
}

resource "azurerm_key_vault_secret" "anthropic_api_key" {
  name         = "anthropic-api-key"
  value        = "REPLACE_AFTER_APPLY"
  key_vault_id = azurerm_key_vault.main.id

  lifecycle {
    ignore_changes = [value]
  }

  depends_on = [azurerm_role_assignment.kv_admin_for_runner]
}

resource "azurerm_key_vault_secret" "trading_economics_api_key" {
  name         = "trading-economics-api-key"
  value        = "REPLACE_AFTER_APPLY"
  key_vault_id = azurerm_key_vault.main.id

  lifecycle {
    ignore_changes = [value]
  }

  depends_on = [azurerm_role_assignment.kv_admin_for_runner]
}
