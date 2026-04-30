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

variable "alert_email" {
  description = "Email address for the Phase 1 action group. Empty == no email receiver wired (action group still created so alerts fire and can be tailed via the portal)."
  type        = string
  default     = ""
}
