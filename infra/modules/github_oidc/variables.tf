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

variable "github_org" {
  description = "GitHub organization or user that owns the repo."
  type        = string
  default     = "codesidh"
}

variable "github_repo" {
  description = "GitHub repository name (case-sensitive on the OIDC subject claim)."
  type        = string
  default     = "level-sweep-agent"
}

variable "aks_resource_group_id" {
  description = "Resource ID of the AKS resource group — scope for GHA Contributor role."
  type        = string
  default     = ""
}

variable "acr_id" {
  description = "Resource ID of the ACR — scope for GHA AcrPush role."
  type        = string
  default     = ""
}

variable "key_vault_id" {
  description = "Resource ID of the Key Vault — scope for GHA Key Vault Secrets User role."
  type        = string
  default     = ""
}
