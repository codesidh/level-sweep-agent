# LevelSweepAgent — Requirements Specification

**Version**: 1.0 (Locked)
**Date**: 2026-04-29
**Status**: Requirements complete — pending architecture sign-off before build

---

## 1. Mission

Build a **fully automated execution agent** that trades 0DTE SPY options based on a four-level liquidity sweep strategy, validated by an EMA trend stack. The agent must operate without human intervention during market hours and enforce all risk discipline in code.

---

## 2. Core Thesis

> When price tests a key level (PDH/PDL/PMH/PML) and the EMA(13/48/200) trend stack is aligned, a confirmed close beyond that level signals a high-probability move toward the next level. Weak reactions at swept levels indicate institutional intent to continue trend; the EMA stack filters out fakeouts and choppy regimes.

---

## 3. Universe & Vehicle

| Item | Value |
|---|---|
| **Phase 1 instrument** | SPY only |
| **Future instruments** | SPX, QQQ, IWM (same logic) |
| **Vehicle** | 0DTE options (CALL or PUT) |
| **Strike rule** | First ITM strike (closest in-the-money) |

---

## 4. Reference Levels

Computed once per session, finalized by **09:29:30 ET**:

| Level | Definition | Window |
|---|---|---|
| **PDH** | Previous day High | RTH only: 09:30–16:00 ET prior day |
| **PDL** | Previous day Low | RTH only: 09:30–16:00 ET prior day |
| **PMH** | Pre-market / overnight High | 16:01 ET prior day → 09:29 ET today |
| **PML** | Pre-market / overnight Low | 16:01 ET prior day → 09:29 ET today |

---

## 5. Timeframes

| Timeframe | Purpose |
|---|---|
| **15-min** | Entry decision (candle closes drive setup logic) |
| **2-min** | EMA stack evaluation, trade management, stop-loss execution |
| **Daily** | ATR(14) used as buffer & gap reference |

---

## 6. Trend Filter (2-min EMA stack)

EMAs computed continuously on 2-min SPY chart.

### 6.1 Validity test

Let `gap_top    = EMA13 − EMA48`
Let `gap_bottom = EMA48 − EMA200`

**Bullish stack (CALLs allowed)**:
```
EMA13 > EMA48 > EMA200
AND gap_top    ≥ 0.3 × ATR(14)
AND gap_bottom ≥ 0.3 × ATR(14)
```

**Bearish stack (PUTs allowed)**:
```
EMA13 < EMA48 < EMA200
AND |gap_top|    ≥ 0.3 × ATR(14)
AND |gap_bottom| ≥ 0.3 × ATR(14)
```

**Otherwise**: choppy regime → **NO TRADE** (skip all signals).

### 6.2 EMA48 hold-exception (used in stop-loss)

If `|EMA13 − EMA48| < 0.5 × ATR(14)`, the stop reference shifts from EMA13 to EMA48 (see §9).

---

## 7. Sweep Validation

A level is "swept" only when a **15-min candle closes** beyond it by at least `BUFFER`.

```
BUFFER = 0.2 × ATR(14)
```

| Direction | Condition |
|---|---|
| Bullish breakout | `15m_close ≥ PMH + BUFFER` (or PDH + BUFFER) |
| Bearish breakdown | `15m_close ≤ PML − BUFFER` (or PDL − BUFFER) |

A bare wick or tick-cross **does not** qualify.

---

## 8. Entry Logic

Evaluated on each 15-min candle close, starting **09:45 ET**.

### 8.1 Decision matrix

| 9:45 candle close | EMA stack | Action |
|---|---|---|
| `≥ PMH + BUFFER` | Bullish | **Enter CALL** → target PDH (next resistance) |
| `PML ≤ close ≤ PMH` (mid-range, bouncing off PML) | Bullish | **Enter CALL** → target PMH |
| `≤ PML − BUFFER` | Bearish | **Wait for 10:00 ET candle confirmation** |
| Inside levels w/o boundary break | any | **NO TRADE** (re-evaluate next 15-min close) |

### 8.2 Below-PML confirmation rule

For all setups where 9:45 closes ≤ PML − BUFFER:

```
If 10:00 ET 15m_close ≤ PDH:
    Enter PUT → target PDL
Else:
    NO TRADE (level held)
```

### 8.3 Continuation scans

If no trade fires at 9:45/10:00, the engine continues evaluating each subsequent 15-min close (10:15, 10:30, ..., until cutoff) using the same matrix logic, applied to the latest level state.

### 8.4 Entry cutoff

**No new entries after 14:30 ET.** Existing positions continue under §9–§10.

---

## 9. Stop-Loss

### 9.1 Trigger
- **CALL**: 2-min candle **close** below `EMA13`
- **PUT**: 2-min candle **close** above `EMA13`

### 9.2 EMA48 exception
If `|EMA13 − EMA48| < 0.5 × ATR(14)` at the moment of trigger evaluation:
- Use **EMA48** as stop reference instead of EMA13
- Hold the trade until the 2-min close violates EMA48

### 9.3 Execution
- Exit immediately on 2-min candle close that satisfies the trigger
- Order type: **market** (see §13)

---

## 10. Profit Target & Trailing Stop

### 10.1 Activation
At unrealized P&L of **+30% of original premium paid**, activate the trailing stop.

### 10.2 Ratchet rule
For every additional **+5%** advance in unrealized P&L, raise the trailing floor by **5%**.

| Unrealized P&L | Trailing floor |
|---|---|
| +30% (activation) | +25% |
| +35% | +30% |
| +40% | +35% |
| +45% | +40% |
| ... | ... |

The trailing floor is **monotonic** — it never decreases.

### 10.3 Exit trigger
When unrealized P&L falls back to the trailing floor, **exit at market**.

### 10.4 Worked example
Entry premium: $1000. At $1300 (+30%) → trail = $1250 (+25%). If price advances to $1400 (+40%) → trail = $1350 (+35%). If price retraces and crosses $1350 → exit booked at +35%.

---

## 11. Risk Management

### 11.1 Position sizing
**Per-trade dollar size** = `5% × start_of_day_portfolio_equity`

Example: $5,000 equity → $250 per trade. Computed once at 09:29 ET; remains fixed throughout the trading day.

### 11.2 Daily risk budget
**Max daily realized loss** = `2% × start_of_day_portfolio_equity`

Example: $5,000 equity → $100 max daily loss.

### 11.3 Re-entry rule
Re-entries are allowed throughout the day **as long as**:
```
cumulative_realized_loss_today < daily_risk_budget
```

When `cumulative_realized_loss_today ≥ daily_risk_budget` → **HALT** new entries for the day. Open positions continue under §9 / §10.

### 11.4 Halt persistence
Once halted by risk breach, the agent does not resume until the **next trading day** (re-armed at 09:00 ET on next session).

---

## 12. Filters / No-Trade Conditions

| Filter | Source | Behavior |
|---|---|---|
| **News blackout** | Trading Economics API (FOMC, CPI, NFP, major scheduled releases) | Skip entire trading day |
| **Choppy regime** | EMA stack invalid (§6.1) | Skip individual setup; re-check next candle |
| **Pre-09:45 ET** | Time gate | No entries |
| **Post-14:30 ET** | Time gate | No new entries; existing positions managed |
| **Daily risk budget breached** | Internal state (§11) | Halt new entries |

---

## 13. Order Execution

| Concern | Decision |
|---|---|
| **Broker** | Alpaca |
| **Order type** | Market orders (entry + exit) |
| **Concurrency** | One open position at a time (no pyramiding) |
| **Strike selection** | First ITM strike on 0DTE expiry |
| **Liquidity check** | Reject strike if bid-ask spread exceeds threshold (TBD during build; sensible default ≤ $0.10 absolute or 5% of mid) |

---

## 14. End-of-Day

- **15:55 ET hard close**: any open position is force-flattened via market order
- **16:05 ET reconciliation**: realized P&L computed, trade journal persisted, daily report emitted

---

## 15. Data Requirements

| Feed | Provider | Use |
|---|---|---|
| SPY 1-min OHLCV (RTH + extended hours) | Alpaca SIP WS | Aggregated to 2-min and 15-min |
| SPY daily bars | Alpaca REST | ATR(14) computation |
| SPY 0DTE option chain (real-time NBBO) | Alpaca REST snapshot (1s polling) | Strike selection, fills, P&L |
| Economic calendar | Trading Economics | News blackout filter |
| Account state | Alpaca Trading API | Equity at 09:29 ET, position state, fills |

**Pre-market data assumption**: paid Alpaca Algo Trader Plus (SIP) tier delivers complete 16:01 → 09:29 ET overnight data prior to 09:30 ET each session.

---

## 16. Operational Discipline

| Principle | Implementation |
|---|---|
| **Constant size daily** | §11.1 — fixed at 09:29 ET |
| **One profitable trade goal** | Engine continues seeking setups until budget halt or 14:30 ET; no greed override |
| **No emotion / no manual override** | Agent runs autonomously; no human approval gate during session |
| **News blackout** | §12 — no trading on calendar event days |

---

## 17. Out of Scope (Phase 1)

- Multiple instruments simultaneously
- Multi-leg option strategies (verticals, iron condors)
- Pyramiding / averaging in
- Discretionary override UI
- Manual signal mode / paper-only mode (agent is live-execution-first)
- Scalping mode (mentioned by SME as separate playbook)
- Marketing/distribution to third-party users

---

## 18. Acceptance Criteria

The build is considered complete when:

1. Agent computes PDH/PDL/PMH/PML correctly from Alpaca SIP data each session
2. Agent evaluates 15-min and 2-min charts in real time
3. EMA stack and ATR-buffer rules match this spec exactly
4. Entry, stop-loss, and trailing-stop logic execute on Alpaca paper account for ≥ 20 sessions without intervention
5. Daily risk budget enforcement verified by injecting simulated losses
6. End-of-day flatten runs reliably at 15:55 ET
7. Trade journal records every decision (entry, exit, signal evaluation, no-trade reason)
8. Backtest harness reproduces live-paper results within ±2% on the same period
