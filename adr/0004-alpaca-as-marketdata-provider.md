# ADR-0004: Alpaca as the single market-data + execution provider

**Status**: accepted
**Date**: 2026-04-30
**Deciders**: owner
**Supersedes**: parts of `architecture-spec.md` v2.2 §9.1 / §15 that referenced Polygon

## Context

Phase 0 scaffolding shipped a Polygon WebSocket adapter (`PolygonStream`) on the assumption that Polygon Stocks Starter ($29/mo) would cover Phase 1's real-time SPY data needs.

Empirical testing during Phase 1 soak preparation revealed:

1. **Polygon Stocks Starter ($29/mo) is delayed-only** — even per-minute aggregates require connecting to `wss://delayed.polygon.io/stocks` with 15-minute lag. The Polygon error message said this in plain text:
   > "You don't have access real-time data … your plan only includes delayed data"
2. **Real-time access on Polygon requires Stocks Advanced ($199/mo)** — full T/Q WebSocket + real-time aggregates.
3. **Alpaca SIP feed (~$99/mo Algo Trader Plus) provides equivalent consolidated tape** with:
   - Real-time stock trades + quotes WebSocket via `wss://stream.data.alpaca.markets/v2/sip` (verified: 394 trades + 1990 quotes / 30s)
   - Real-time options NBBO via REST `/v1beta1/options/snapshots/{underlying}` (verified, ms-fresh timestamps)
   - 0DTE options chain via Trading API `/v2/options/contracts` (verified, multiple SPY 0DTE contracts returned)
4. **Alpaca is already the chosen broker** for execution (see `architecture-spec.md` §13). Single-provider for both data + execution simplifies operations.

The Phase 3 trail manager wants real-time options NBBO via WebSocket. Alpaca's OPRA WS feed (`/v1beta1/opra`) closed connections immediately under the current Algo Trader Plus subscription — likely a separate add-on. **Mitigation**: REST polling of the chain snapshot at 1-second intervals provides equivalent freshness for the trail-floor ratchet logic in `requirements.md` §10 (option premium moves at most a few % per second on 0DTE; 1s polling cannot miss a meaningful ratchet event).

## Decision

**Single-provider on Alpaca for both market data and execution.**

- Stocks data: Alpaca SIP (`wss://stream.data.alpaca.markets/v2/sip`) — paid Algo Trader Plus
- Options chain (Phase 2 strike selector): Alpaca REST `/v1beta1/options/snapshots/{underlying}`
- Options NBBO (Phase 3 trail manager): Alpaca REST polling at 1s — upgrade to OPRA WS later if proven necessary
- Execution (Phase 3+): Alpaca Trading API
- All traffic uses `APCA-API-KEY-ID` + `APCA-API-SECRET-KEY` header auth

**Polygon code is removed entirely** from the codebase. No abstraction layer for "future provider swap" — that would be premature complexity. If we ever switch back, this ADR documents the relevant interfaces.

## Consequences

### Positive

- **~$100/mo savings** vs Polygon Stocks Advanced ($199 → ~$99). Numbers vary by Alpaca tier; verified live.
- **Single API to monitor**: one provider for data + execution = correlated failures (which we'd handle with a CB anyway), one bill, one dashboard, one set of credentials.
- **Tighter integration**: Alpaca's snapshot endpoint returns `latestQuote + latestTrade + greeks` in a single call, useful for AI Sentinel context (Phase 5).
- **Phase B simpler**: per-user Alpaca OAuth + per-user data entitlement (paid by user) is cleaner than a multi-provider Phase B story.
- **No "Non-pros only" quirk** that Polygon Advanced had — Alpaca handles SIP licensing differently.

### Negative

- **Single point of failure**: an Alpaca outage takes down both data ingest and order placement. Mitigations: (a) Connection FSM auto-halts new entries on UNHEALTHY; (b) Phase 7 resilience hardening adds explicit recovery paths.
- **Newer options data offering**: Alpaca added options in 2024; Polygon has years of options history. Risk: edge cases in 0DTE strike selection (delisted symbols, weird tickers) may surface later. Mitigation: log all unexpected options responses and add to test fixtures.
- **Phase 3 trail uses REST polling, not WS**: 1-second poll cadence vs ms-fresh WS. Acceptable per §10 ratchet semantics; revisit if soak shows missed ratchets.
- **OPRA WS upgrade may still be needed for Phase 3**: if 1s polling proves inadequate. Cost TBD when that decision matures.

## Alternatives Considered

- **Stay on Polygon Stocks Advanced ($199/mo)** — rejected: $100/mo more for no architectural benefit when Alpaca covers our needs; single-provider simplification outweighs.
- **Hybrid: Polygon for historical/REST + Alpaca for execution + Alpaca SIP for live** — rejected: two providers means two failure modes to monitor for marginal data quality benefit. Alpaca's REST historical is sufficient.
- **Free Alpaca IEX feed** — rejected: ~3% market volume insufficient for accurate PDH/PDL/PMH/PML; high/low set on NYSE/NASDAQ/ARCA (where most volume trades) would be missed.
- **Provider abstraction with Polygon kept as fallback** — rejected: premature complexity. The interfaces in `marketdata.api.*` (TickListener, WsTransport) are provider-agnostic so a future switch back is bounded work, but maintaining two impls now adds no value.

## References

- `requirements.md` §13 (Order Execution)
- `architecture-spec.md` v2.3 §9.1 (Market Data Service), §15 (Tech Stack), §17.1 (Resilience), §21 (Build Phases)
- `.claude/skills/alpaca-trading-api/SKILL.md` (auto-loading reference)
- `docs/alpaca-trading-api-skill.md` (deep-dive)
- Smoke-test runs verifying entitlement: `25177571957` (SIP stocks) and `25177938150` (options chain via REST)
