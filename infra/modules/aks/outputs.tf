output "cluster_id" {
  description = "Resource ID of the AKS cluster."
  value       = azurerm_kubernetes_cluster.main.id
}

output "cluster_name" {
  description = "Name of the AKS cluster — pass to `az aks get-credentials --name`."
  value       = azurerm_kubernetes_cluster.main.name
}

output "cluster_resource_group" {
  description = "Resource group containing the AKS cluster (NOT the node-resource-group)."
  value       = azurerm_resource_group.aks.name
}

output "cluster_resource_group_id" {
  description = "Resource ID of the AKS RG — used as scope for GHA Contributor role assignment."
  value       = azurerm_resource_group.aks.id
}

output "node_resource_group" {
  description = "Auto-generated resource group AKS uses for node-level resources."
  value       = azurerm_kubernetes_cluster.main.node_resource_group
}

output "oidc_issuer_url" {
  description = "OIDC issuer URL — used as the `issuer` for federated workload identities."
  value       = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "kubelet_object_id" {
  description = "Object ID of the kubelet managed identity — grant Key Vault Secrets User if pods need direct KV access."
  value       = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

output "kubelet_client_id" {
  description = "Client ID of the kubelet managed identity — used by the Azure Key Vault CSI driver / workload-identity binding (Phase 1; Phase 7 introduces a dedicated workload MI per service)."
  value       = azurerm_kubernetes_cluster.main.kubelet_identity[0].client_id
}
