# =============================================================================
# Root composition module.
# Phase 0 — empty. Environments under environments/{dev,stage,prod} compose
# the leaf modules under modules/. This file exists so `terraform init` /
# `validate` succeed at the repo root for shared lint checks.
# =============================================================================

locals {
  common_tags = merge(
    {
      project     = var.project
      environment = var.environment
      managed-by  = "terraform"
      repo        = "LevelSweepAgent"
    },
    var.tags
  )
}

# Intentionally no resources at the root level — see environments/*.
# Future: cross-environment shared resources (e.g., DNS zone) may live here.
