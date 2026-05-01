variable "project" {
  description = "Project short name."
  type        = string
}

variable "environment" {
  description = "Deployment environment."
  type        = string
}

variable "location" {
  description = "Region for the resource group. Static Web App compute region is set separately via `swa_location`."
  type        = string
}

variable "swa_location" {
  description = "Static Web App compute region. Free tier is restricted to a small set; defaults to eastus2 which is closest to the dev environment's eastus RG."
  type        = string
  default     = "eastus2"
}

variable "tags" {
  description = "Resource tags."
  type        = map(string)
  default     = {}
}
