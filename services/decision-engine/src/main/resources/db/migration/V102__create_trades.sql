-- Architecture-spec §13.1: one row per Trade FSM instance. The Trade Saga
-- (Phase 2 Step 4 / S6) writes the entry/exit side of a position and the
-- canonical FSM state for replay reconstruction.
--
-- Idempotent (IF NOT EXISTS). No GO separators — flyway-sqlserver parses
-- pure-DDL batches as a single script.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'trades')
BEGIN
  CREATE TABLE trades (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    session_date    DATE          NOT NULL,
    symbol          VARCHAR(16)   NOT NULL,
    contract_symbol VARCHAR(32)   NULL,
    side            VARCHAR(8)    NOT NULL,
    entered_at      DATETIME2(3)  NULL,
    exited_at       DATETIME2(3)  NULL,
    entry_price     DECIMAL(18,4) NULL,
    exit_price      DECIMAL(18,4) NULL,
    quantity        INT           NOT NULL,
    realized_pnl    DECIMAL(18,4) NULL,
    fsm_state       VARCHAR(16)   NOT NULL,
    fsm_version     INT           NOT NULL DEFAULT 1,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME()
  );
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_trades_tenant_session' AND object_id = OBJECT_ID('trades'))
BEGIN
  CREATE INDEX ix_trades_tenant_session ON trades(tenant_id, session_date);
END;

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'ix_trades_state_open' AND object_id = OBJECT_ID('trades'))
BEGIN
  CREATE INDEX ix_trades_state_open ON trades(tenant_id, fsm_state)
    WHERE fsm_state IN ('PROPOSED','ENTERED','ACTIVE','EXITING');
END;
