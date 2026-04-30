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
