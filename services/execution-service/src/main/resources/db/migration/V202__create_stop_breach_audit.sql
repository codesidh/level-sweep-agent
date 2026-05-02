-- ADR-0005 §3: per-trigger audit row written by the Stop Watcher (Phase 3
-- Step 4) every time a 2-min bar close violates the §9 stop reference. One
-- row per (tenant_id, trade_id) trigger evaluation that fires; non-firing
-- evaluations do NOT write a row (operators query Kafka consumer lag if
-- they need pure throughput).
--
-- V201+ prefix is execution-service's reserved range on the shared MS SQL
-- database — V001-V099 are market-data-service, V100-V199 are decision-engine.
-- Idempotent (IF NOT EXISTS). No GO separators — flyway-sqlserver parses
-- pure-DDL batches as a single script.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'stop_breach_audit')
BEGIN
  CREATE TABLE stop_breach_audit (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    trade_id        VARCHAR(64)   NOT NULL,
    alpaca_order_id VARCHAR(64)   NOT NULL,
    contract_symbol VARCHAR(32)   NOT NULL,
    bar_timestamp   DATETIME2(3)  NOT NULL,
    bar_close       DECIMAL(18,4) NOT NULL,
    stop_reference  VARCHAR(8)    NOT NULL,    -- 'EMA13' | 'EMA48'
    triggered_at    DATETIME2(3)  NOT NULL,
    correlation_id  VARCHAR(64)   NOT NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME()
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_stop_breach_tenant_trade' AND object_id = OBJECT_ID('stop_breach_audit'))
BEGIN
  CREATE INDEX ix_stop_breach_tenant_trade ON stop_breach_audit(tenant_id, trade_id);
END;
