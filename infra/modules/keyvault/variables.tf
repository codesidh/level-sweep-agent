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

variable "azure_tenant_id" {
  description = "Azure AD tenant id (Key Vault binding)."
  type        = string
  default     = ""
}

variable "tags" {
  description = "Resource tags."
  type        = map(string)
  default     = {}
}
