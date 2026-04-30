-- Architecture-spec §13.1: daily_state holds the four reference levels
-- (PDH/PDL/PMH/PML) plus the pre-RTH compute timestamps and (in S5+) the
-- columns the Decision Engine reads. Phase 1 ships the level columns only.
--
-- Idempotent (IF NOT EXISTS) so this migration tolerates being applied
-- multiple times against an already-bootstrapped MS SQL instance during
-- local dev. Flyway tracks applied versions in flyway_schema_history.
--
-- No GO separators — the flyway-sqlserver driver parses these blocks fine
-- as a single batched script for pure DDL.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'daily_state')
BEGIN
  CREATE TABLE daily_state (
    tenant_id      VARCHAR(64)   NOT NULL,
    session_date   DATE          NOT NULL,
    symbol         VARCHAR(16)   NOT NULL,
    pdh            DECIMAL(18,4) NULL,
    pdl            DECIMAL(18,4) NULL,
    pmh            DECIMAL(18,4) NULL,
    pml            DECIMAL(18,4) NULL,
    created_at     DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at     DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_daily_state PRIMARY KEY (tenant_id, session_date)
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_daily_state_symbol' AND object_id = OBJECT_ID('daily_state'))
BEGIN
  CREATE INDEX ix_daily_state_symbol ON daily_state(symbol);
END;
