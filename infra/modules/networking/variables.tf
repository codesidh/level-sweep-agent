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

variable "enable_nat_gateway" {
  description = "Whether to provision a NAT Gateway + static egress IP. Phase 1 dev runs without (~$35/mo savings); Phase 7 enables when partner allowlists require a deterministic egress IP."
  type        = bool
  default     = false
}
