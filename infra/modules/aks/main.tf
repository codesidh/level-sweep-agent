# =============================================================================
# Module: AKS cluster.
# Phase 1 dev-grade: single system node pool (2 x Standard_D4s_v5) sized for
# low TCO. Workload identity + OIDC issuer enabled so the Key Vault CSI
# driver can mount secrets via federated identities.
# Phase 7+ splits the system pool into the per-workload pools described in
# architecture-spec §16.2 (system / hot / ai / warm / kafka / ops).
# =============================================================================

resource "azurerm_resource_group" "aks" {
  name     = "rg-${var.project}-${var.environment}-aks"
  location = var.location
  tags     = var.tags
}

resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-${var.project}-${var.environment}"
  location            = azurerm_resource_group.aks.location
  resource_group_name = azurerm_resource_group.aks.name
  dns_prefix          = "${var.project}-${var.environment}"
  kubernetes_version  = var.kubernetes_version

  default_node_pool {
    name           = "system"
    node_count     = 2
    vm_size        = "Standard_D4s_v5"
    vnet_subnet_id = var.subnet_aks_id

    # Phase 1 dev: keep system pool open for workloads to avoid the cost of
    # a second pool. Phase 7 splits responsibilities; flip this to true then.
    only_critical_addons_enabled = false
  }

  identity {
    type = "SystemAssigned"
  }

  # Workload identity (federation) — required for Key Vault CSI secret store.
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  azure_policy_enabled = true

  network_profile {
    network_plugin = "azure"
    network_policy = "azure"
    service_cidr   = "10.43.0.0/16"
    dns_service_ip = "10.43.0.10"
    # Egress traffic exits through the user-assigned NAT Gateway provisioned
    # by the networking module — gives a deterministic IP for Alpaca etc.
    outbound_type = "userAssignedNATGateway"
  }

  oms_agent {
    log_analytics_workspace_id = var.log_analytics_workspace_id
  }

  tags = var.tags
}

# AcrPull on the cluster's kubelet identity so pods can pull from the project
# ACR without imagePullSecrets. The kubelet identity is created automatically
# by AKS and exposed via kubelet_identity[0].object_id.
resource "azurerm_role_assignment" "aks_acr_pull" {
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}
