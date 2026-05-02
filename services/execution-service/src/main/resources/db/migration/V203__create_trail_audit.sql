-- ADR-0005 §4: per-event audit row written by the Trail Manager (Phase 3
-- Step 5) on every ratchet AND every exit trigger. Two row kinds, one shape:
--   outcome = 'RATCHET' — the floor advanced; new_floor_pct is the new floor
--   outcome = 'EXIT'    — NBBO mid retraced to the floor for the sustainment
--                          window; exit_floor_pct is where the trade exited
-- Replay parity asserts on this table — a recorded session must reproduce
-- byte-for-byte under the replay harness.
--
-- V201+ prefix is execution-service's reserved range on the shared MS SQL
-- database. Idempotent (IF NOT EXISTS). No GO separators — flyway-sqlserver
-- parses pure-DDL batches as a single script.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'trail_audit')
BEGIN
  CREATE TABLE trail_audit (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    trade_id        VARCHAR(64)   NOT NULL,
    contract_symbol VARCHAR(32)   NOT NULL,
    observed_at     DATETIME2(3)  NOT NULL,
    outcome         VARCHAR(8)    NOT NULL,    -- 'RATCHET' | 'EXIT'
    nbbo_mid        DECIMAL(18,4) NOT NULL,
    upl_pct         DECIMAL(10,4) NULL,        -- non-null on RATCHET rows
    new_floor_pct   DECIMAL(10,4) NULL,        -- non-null on RATCHET rows
    exit_floor_pct  DECIMAL(10,4) NULL,        -- non-null on EXIT rows
    correlation_id  VARCHAR(64)   NOT NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME()
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_trail_audit_tenant_trade' AND object_id = OBJECT_ID('trail_audit'))
BEGIN
  CREATE INDEX ix_trail_audit_tenant_trade ON trail_audit(tenant_id, trade_id);
END;
