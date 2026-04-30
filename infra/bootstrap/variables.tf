variable "subscription_id" {
  description = "Target Azure subscription ID for the tfstate storage."
  type        = string
}

variable "project" {
  description = "Project short-name; used in resource names (max 11 chars to fit storage account constraints)."
  type        = string
  default     = "lvlswp"

  validation {
    condition     = length(var.project) <= 11 && can(regex("^[a-z0-9]+$", var.project))
    error_message = "project must be lowercase alphanumeric, max 11 chars (storage account naming)."
  }
}

variable "environment" {
  description = "Environment short-name (dev / stage / prod)."
  type        = string

  validation {
    condition     = contains(["dev", "stage", "prod"], var.environment)
    error_message = "environment must be one of: dev, stage, prod."
  }
}

variable "location" {
  description = "Azure region."
  type        = string
  default     = "eastus"
}

variable "admin_object_ids" {
  description = "Azure AD object IDs (users / groups / SPs) granted Storage Blob Data Contributor on the tfstate account."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to all bootstrap resources."
  type        = map(string)
  default = {
    project   = "level-sweep-agent"
    component = "tfstate-bootstrap"
    managedBy = "terraform"
  }
}
