# =============================================================================
# Module: networking (VNET, subnets, NAT gateway).
# Provides:
#   - Private VNET with subnets for AKS, private endpoints, and a reserved
#     Bastion subnet (Bastion is dormant in Phase 1).
#   - NAT Gateway with a deterministic egress public IP — used by AKS to reach
#     external services (Alpaca, Anthropic, Trading Economics) that allowlist
#     egress IPs.
# Phase 7+ tightens this with VNET peering for APIM and additional private
# endpoints (MS SQL, Mongo, Key Vault) per architecture-spec §16.3.
# =============================================================================

resource "azurerm_resource_group" "net" {
  name     = "rg-${var.project}-${var.environment}-net"
  location = var.location
  tags     = var.tags
}

resource "azurerm_virtual_network" "main" {
  name                = "vnet-${var.project}-${var.environment}"
  location            = azurerm_resource_group.net.location
  resource_group_name = azurerm_resource_group.net.name
  address_space       = ["10.42.0.0/16"]
  tags                = var.tags
}

resource "azurerm_subnet" "aks" {
  name                 = "snet-aks"
  resource_group_name  = azurerm_resource_group.net.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.42.0.0/22"]
}

resource "azurerm_subnet" "pe" {
  name                 = "snet-pe"
  resource_group_name  = azurerm_resource_group.net.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.42.4.0/27"]

  # Required for private endpoints to attach to the subnet.
  private_endpoint_network_policies = "Disabled"
}

resource "azurerm_subnet" "bastion" {
  # Reserved CIDR for Phase 7+ Azure Bastion. The subnet name MUST be
  # AzureBastionSubnet for Bastion to attach; we use snet-bastion now and
  # rename in Phase 7 when Bastion is provisioned (CIDR is preserved).
  name                 = "snet-bastion"
  resource_group_name  = azurerm_resource_group.net.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.42.4.32/27"]
}

# NAT Gateway is optional. Phase 1 dev runs without one to save ~$35/mo —
# Alpaca / Anthropic / Trading Economics don't require IP allowlisting at
# the tier we use, so AKS load-balancer SNAT (free) is sufficient. Phase 7
# enables NAT when a deterministic egress IP becomes a requirement.
resource "azurerm_public_ip" "nat" {
  count               = var.enable_nat_gateway ? 1 : 0
  name                = "pip-${var.project}-${var.environment}-nat"
  location            = azurerm_resource_group.net.location
  resource_group_name = azurerm_resource_group.net.name
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = var.tags
}

resource "azurerm_nat_gateway" "main" {
  count               = var.enable_nat_gateway ? 1 : 0
  name                = "ng-${var.project}-${var.environment}"
  location            = azurerm_resource_group.net.location
  resource_group_name = azurerm_resource_group.net.name
  sku_name            = "Standard"
  tags                = var.tags
}

resource "azurerm_nat_gateway_public_ip_association" "main" {
  count                = var.enable_nat_gateway ? 1 : 0
  nat_gateway_id       = azurerm_nat_gateway.main[0].id
  public_ip_address_id = azurerm_public_ip.nat[0].id
}

resource "azurerm_subnet_nat_gateway_association" "aks" {
  count          = var.enable_nat_gateway ? 1 : 0
  subnet_id      = azurerm_subnet.aks.id
  nat_gateway_id = azurerm_nat_gateway.main[0].id
}
