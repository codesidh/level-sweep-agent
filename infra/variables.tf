# =============================================================================
# Root-level variables. Environments override via terraform.tfvars.
# =============================================================================

variable "project" {
  description = "Project short name; used as resource prefix."
  type        = string
  default     = "levelsweep"
}

variable "environment" {
  description = "Deployment environment (dev / stage / prod)."
  type        = string
  validation {
    condition     = contains(["dev", "stage", "prod"], var.environment)
    error_message = "environment must be one of: dev, stage, prod."
  }
}

variable "location" {
  description = "Azure region. Default eastus per arch-spec §16.1."
  type        = string
  default     = "eastus"
}

variable "tenant_id" {
  description = "Azure AD tenant id (Auth0 tenant lives separately)."
  type        = string
  default     = ""
}

variable "subscription_id" {
  description = "Azure subscription id."
  type        = string
  default     = ""
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}
