# =============================================================================
# Root outputs (intentionally minimal).
# =============================================================================

output "project" {
  description = "Project short name."
  value       = var.project
}

output "environment" {
  description = "Deployment environment."
  value       = var.environment
}

output "common_tags" {
  description = "Tags applied to all resources."
  value       = local.common_tags
}
