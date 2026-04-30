# =============================================================================
# Module: github_oidc.
# Provisions the user-assigned managed identity that GitHub Actions assumes
# via OIDC federation, plus the federated credentials for the relevant
# trigger contexts and the role assignments scoped to AKS / ACR / KV.
#
# After apply, set the following GitHub secrets:
#   AZURE_CLIENT_ID       = output `client_id`
#   AZURE_TENANT_ID       = output `tenant_id`
#   AZURE_SUBSCRIPTION_ID = `az account show --query id -o tsv`
# =============================================================================

resource "azurerm_resource_group" "gha" {
  name     = "rg-${var.project}-${var.environment}-gha"
  location = var.location
  tags     = var.tags
}

resource "azurerm_user_assigned_identity" "gha" {
  name                = "id-${var.project}-${var.environment}-gha"
  resource_group_name = azurerm_resource_group.gha.name
  location            = azurerm_resource_group.gha.location
  tags                = var.tags
}

# -----------------------------------------------------------------------------
# Federated credentials. Issuer + audience are GitHub's OIDC defaults.
# -----------------------------------------------------------------------------

resource "azurerm_federated_identity_credential" "main" {
  name                = "gha-main"
  resource_group_name = azurerm_resource_group.gha.name
  parent_id           = azurerm_user_assigned_identity.gha.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_org}/${var.github_repo}:ref:refs/heads/main"
}

resource "azurerm_federated_identity_credential" "pr" {
  name                = "gha-pr"
  resource_group_name = azurerm_resource_group.gha.name
  parent_id           = azurerm_user_assigned_identity.gha.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_org}/${var.github_repo}:pull_request"
}

# Optional: tag-based releases (Phase 7 release builds). Created up front
# so the GHA token swap path is ready when release.yml lands.
resource "azurerm_federated_identity_credential" "tag" {
  name                = "gha-tag"
  resource_group_name = azurerm_resource_group.gha.name
  parent_id           = azurerm_user_assigned_identity.gha.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_org}/${var.github_repo}:ref:refs/tags/*"
}

# -----------------------------------------------------------------------------
# Role assignments. Scoped narrowly: Contributor on the AKS RG only (not the
# whole subscription), AcrPush on the ACR resource, and Key Vault Secrets
# User so the deploy workflow can read connection strings if needed.
# -----------------------------------------------------------------------------

resource "azurerm_role_assignment" "gha_aks_contributor" {
  scope                = var.aks_resource_group_id
  role_definition_name = "Contributor"
  principal_id         = azurerm_user_assigned_identity.gha.principal_id
}

resource "azurerm_role_assignment" "gha_acr_push" {
  scope                = var.acr_id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.gha.principal_id
}

resource "azurerm_role_assignment" "gha_kv_secrets_user" {
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.gha.principal_id
}
