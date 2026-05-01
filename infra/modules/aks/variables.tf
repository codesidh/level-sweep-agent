variable "project" {
  description = "Project short name."
  type        = string
}

variable "environment" {
  description = "Deployment environment."
  type        = string
}

variable "location" {
  description = "Azure region."
  type        = string
}

variable "tags" {
  description = "Resource tags."
  type        = map(string)
  default     = {}
}

variable "kubernetes_version" {
  description = "AKS control-plane Kubernetes version. Pin via this variable so node-pool upgrades are explicit."
  type        = string
  default     = "1.30"
}

variable "subnet_aks_id" {
  description = "ID of the subnet that hosts the AKS node pool."
  type        = string
  default     = ""
}

variable "log_analytics_workspace_id" {
  description = "Log Analytics workspace ID for AKS oms_agent."
  type        = string
  default     = ""
}

variable "acr_id" {
  description = "Resource ID of the ACR — granted AcrPull to the kubelet identity."
  type        = string
  default     = ""
}

variable "node_count" {
  description = "Default node-pool node count. Phase 1 dev uses 1; Phase 7 raises for HA."
  type        = number
  default     = 2
}

variable "vm_size" {
  description = "Default node-pool VM size. Phase 1 dev defaults to a burstable 2-vCPU/8 GB SKU; Phase 7 promotes to Standard_D-series for sustained throughput."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "enable_azure_policy" {
  description = "Whether to enable the Azure Policy add-on. Disable in dev to free node CPU/RAM."
  type        = bool
  default     = true
}

variable "outbound_type" {
  description = "AKS egress strategy. \"loadBalancer\" uses the default LB SNAT (free). \"userAssignedNATGateway\" requires a NAT Gateway provisioned upstream."
  type        = string
  default     = "loadBalancer"
}
