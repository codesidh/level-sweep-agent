-- Architecture-spec §13.1: the Risk FSM (requirements.md §11) requires
-- extra columns on daily_state — starting equity (computed at 09:29 ET),
-- the derived loss budget, the running realized loss, the trade counter,
-- and the FSM state machine columns (HEALTHY / BUDGET_LOW / HALTED).
--
-- The original V001 (market-data-service) created daily_state with the four
-- reference levels only. ALTER TABLE here adds the new columns with safe
-- defaults so the existing LevelsRepository.upsert (which only mentions
-- the original 7 columns + created_at/updated_at) keeps working — defaults
-- fill in the rest. This keeps the two repositories decoupled.
--
-- Idempotent: gates on the presence of `starting_equity` so re-runs are no-ops.

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('daily_state') AND name = 'starting_equity')
BEGIN
  ALTER TABLE daily_state ADD
    starting_equity     DECIMAL(18,4) NULL,
    daily_loss_budget   DECIMAL(18,4) NULL,
    realized_loss       DECIMAL(18,4) NOT NULL DEFAULT 0,
    trades_taken        INT           NOT NULL DEFAULT 0,
    risk_state          VARCHAR(16)   NOT NULL DEFAULT 'HEALTHY',
    halted_at           DATETIME2(3)  NULL,
    halt_reason         VARCHAR(256)  NULL;
END;
