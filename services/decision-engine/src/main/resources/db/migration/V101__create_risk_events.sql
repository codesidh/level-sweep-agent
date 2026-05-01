-- Architecture-spec §13.1: append-only risk-budget consumption + FSM transition log.
-- Decision-engine namespace starts at V100 so its Flyway history does not collide
-- with market-data-service's V001+ on a shared MS SQL database.
--
-- Idempotent (IF NOT EXISTS) so this migration tolerates being applied multiple
-- times against an already-bootstrapped MS SQL instance during local dev.
-- Flyway tracks applied versions in flyway_schema_history.
--
-- No GO separators — the flyway-sqlserver driver parses these blocks fine
-- as a single batched script for pure DDL.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'risk_events')
BEGIN
  CREATE TABLE risk_events (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    session_date    DATE          NOT NULL,
    occurred_at     DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    event_type      VARCHAR(32)   NOT NULL,
    from_state      VARCHAR(16)   NULL,
    to_state        VARCHAR(16)   NULL,
    delta_amount    DECIMAL(18,4) NULL,
    cumulative_loss DECIMAL(18,4) NULL,
    reason          VARCHAR(256)  NULL
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_risk_events_tenant_session' AND object_id = OBJECT_ID('risk_events'))
BEGIN
  CREATE INDEX ix_risk_events_tenant_session ON risk_events(tenant_id, session_date);
END;
