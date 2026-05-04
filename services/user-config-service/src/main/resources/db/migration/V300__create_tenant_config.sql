-- =============================================================================
-- V300 — tenant_config: per-tenant strategy parameters + Phase B feature flags.
--
-- Per architecture-spec §13.1 MS SQL is the system of record for financial
-- state. tenant_config sits alongside tenants / users / daily_state / trades /
-- orders / positions / fills / risk_events / fsm_transitions / agent_decisions
-- in the same DB. Retention: per the §13.1 table, tenant_configs is
-- "append-only history; 7y" — TODAY this table is full-replace (no history
-- table); a Phase 7 follow-up adds tenant_config_history for the 7y audit
-- trail required by SEC 17a-4. The `updated_at` column already supports the
-- migration: the history table will INSERT one row per UPDATE here.
--
-- Phase A: single-user owner (CLAUDE.md guardrail #1) — only one row in this
-- table (tenant_id='OWNER') seeded by user-config-service's OwnerSeed bean
-- on first start. Phase B (multi-tenant SaaS) is gated behind the feature
-- flags stored here AND legal counsel completing the RIA / broker-dealer
-- review.
--
-- Migration version V300 deliberately skips ahead of decision-engine /
-- execution-service ranges so each cold-path service gets its own non-
-- overlapping V-numbers (journal-service is Mongo, no Flyway; user-config-
-- service starts at V300; subsequent cold-path services V310, V320, …).
-- =============================================================================

CREATE TABLE tenant_config (
    -- Multi-tenant primary key. NVARCHAR(64) matches the tenant_id width used
    -- across decision-engine / execution-service / ai-agent-service.
    tenant_id NVARCHAR(64) NOT NULL PRIMARY KEY,

    -- Risk / sizing parameters consumed by the Decision Engine on every
    -- signal evaluation (replay-parity skill: changes here must be paired
    -- with replay coverage). DECIMAL(18,2) for USD amounts.
    daily_loss_budget DECIMAL(18,2) NOT NULL,

    -- Hard cap on entries per RTH session. Default 5 per requirements §11.
    max_trades_per_day INT NOT NULL,

    -- Fraction of equity per entry, e.g. 0.0200 = 2%. Domain enforces
    -- 0 < v <= 1 in the TenantConfig record's compact constructor; this
    -- column allows up to 9.9999 to leave headroom for explicit override
    -- without a future ALTER.
    position_size_pct DECIMAL(5,4) NOT NULL,

    -- Pre-Trade Sentinel veto threshold. CLAUDE.md guardrail #2 mandates
    -- >= 0.85; the column is range-checked by the application, NOT by a
    -- CHECK constraint, so an emergency operator override (drop the
    -- threshold to 0 to disable Sentinel) is one PUT call away rather than
    -- a schema migration.
    sentinel_confidence_threshold DECIMAL(3,2) NOT NULL,

    -- Phase B gating flags as a JSON blob. NVARCHAR(MAX) is the MS SQL
    -- equivalent of TEXT; we don't use NVARCHAR(JSON) (SQL Server 2025+)
    -- because the dev cluster is on SQL Server 2022. Application-layer
    -- (de)serialisation via Jackson — see TenantConfigRepository.
    feature_flags NVARCHAR(MAX) NOT NULL,

    -- Audit timestamps. created_at is set on INSERT and never mutated;
    -- updated_at bumps on every PUT. Phase 7's history table joins on
    -- (tenant_id, updated_at) to materialise the change audit trail.
    created_at DATETIME2 NOT NULL,
    updated_at DATETIME2 NOT NULL,

    -- Schema version of this row's payload. Bumped by the application when
    -- TenantConfig record's shape evolves; old rows are migrated by a
    -- subsequent Flyway migration (e.g. V310__migrate_tenant_config_v2)
    -- which UPDATEs schema_version + the changed columns in lockstep.
    schema_version INT NOT NULL DEFAULT 1
);

-- No additional indexes — tenant_id is the primary key and the only query
-- shape this service exposes is single-row lookup by tenant_id. The
-- multi-tenant-readiness skill forbids cross-tenant scans on financial-state
-- tables so we don't index any other column.
