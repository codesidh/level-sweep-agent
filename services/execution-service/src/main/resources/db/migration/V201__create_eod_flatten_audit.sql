-- Architecture-spec §13.1: per-trade audit row written by the EOD flatten
-- saga (Phase 3 Step 6). One row per trade per session regardless of outcome
-- so operators can reconcile that every position was acknowledged before the
-- 16:00 ET 0DTE auto-exercise window.
--
-- V201+ prefix is execution-service's reserved range on the shared MS SQL
-- database — V001-V099 are market-data-service, V100-V199 are decision-engine.
-- Idempotent (IF NOT EXISTS). No GO separators — flyway-sqlserver parses
-- pure-DDL batches as a single script.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'eod_flatten_attempts')
BEGIN
  CREATE TABLE eod_flatten_attempts (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    session_date    DATE          NOT NULL,
    trade_id        VARCHAR(64)   NOT NULL,
    contract_symbol VARCHAR(32)   NOT NULL,
    attempted_at    DATETIME2(3)  NOT NULL,
    outcome         VARCHAR(16)   NOT NULL,    -- 'FLATTENED' | 'NO_OP' | 'FAILED'
    alpaca_order_id VARCHAR(64)   NULL,
    failure_reason  VARCHAR(256)  NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME()
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_eod_flatten_tenant_session' AND object_id = OBJECT_ID('eod_flatten_attempts'))
BEGIN
  CREATE INDEX ix_eod_flatten_tenant_session ON eod_flatten_attempts(tenant_id, session_date);
END;
