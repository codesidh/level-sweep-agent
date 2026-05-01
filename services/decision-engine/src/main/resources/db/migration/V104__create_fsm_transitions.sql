-- Architecture-spec §13.1: append-only audit row per FSM transition. Phase 2 Step 5
-- (Session FSM + Trade FSM) introduces this table. Same schema serves the Risk FSM
-- (S3) and the future Order/Position/Connection FSMs (Phase 3).
--
-- `fsm_kind` discriminator + `fsm_id` (session_date for SESSION; trade UUID for
-- TRADE) lets a single table back every state-machine without per-FSM migrations.
-- `fsm_version` is the replay-compat marker: when an FSM's transition table is
-- changed in a way that breaks bar-for-bar replay, bump the version and old rows
-- can be quarantined or rejected by the replay harness rather than silently
-- producing wrong-state.
--
-- Idempotent: gates on the table's existence so re-runs are no-ops.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'fsm_transitions')
BEGIN
  CREATE TABLE fsm_transitions (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    session_date    DATE          NOT NULL,
    fsm_kind        VARCHAR(32)   NOT NULL,    -- 'SESSION' | 'TRADE' | 'RISK' (future)
    fsm_id          VARCHAR(64)   NOT NULL,    -- session_date for SESSION; trade UUID for TRADE
    fsm_version     INT           NOT NULL,
    from_state      VARCHAR(32)   NULL,
    to_state        VARCHAR(32)   NOT NULL,
    event           VARCHAR(64)   NOT NULL,    -- e.g., 'MARKET_OPEN', 'FILL_CONFIRMED'
    occurred_at     DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    payload_json    NVARCHAR(MAX) NULL,        -- transition context (lightweight)
    correlation_id  VARCHAR(64)   NULL
  );
  CREATE INDEX ix_fsm_transitions_tenant_session ON fsm_transitions(tenant_id, session_date, fsm_kind);
  CREATE INDEX ix_fsm_transitions_fsm ON fsm_transitions(fsm_kind, fsm_id, occurred_at);
END;
