output "resource_group_name" {
  description = "Name of the networking resource group."
  value       = azurerm_resource_group.net.name
}

output "vnet_id" {
  description = "ID of the virtual network."
  value       = azurerm_virtual_network.main.id
}

output "vnet_name" {
  description = "Name of the virtual network."
  value       = azurerm_virtual_network.main.name
}

output "subnet_aks_id" {
  description = "ID of the AKS subnet."
  value       = azurerm_subnet.aks.id
}

output "subnet_pe_id" {
  description = "ID of the private-endpoints subnet."
  value       = azurerm_subnet.pe.id
}

output "subnet_bastion_id" {
  description = "ID of the reserved Bastion subnet."
  value       = azurerm_subnet.bastion.id
}

output "nat_egress_ip" {
  description = "Static public egress IP — register with Alpaca / Anthropic / Trading Economics allowlists."
  value       = azurerm_public_ip.nat.ip_address
}
