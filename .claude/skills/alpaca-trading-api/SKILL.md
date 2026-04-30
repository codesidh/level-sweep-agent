---
name: alpaca-trading-api
description: Use when working with Alpaca's Trading API or Market Data API — placing orders, querying positions/account, subscribing to WebSocket streams, fetching historical bars, options trading, or anything in the execution-service module. Triggers on Alpaca, alpaca-java, /v2/orders, /v2/account, /v2/positions, options, OCC symbol, paper trading, live trading, APCA-API-KEY-ID, market data WS, SIP feed, IEX feed, 0DTE, buy_to_open, sell_to_close, bracket order, OTOCO, time_in_force, stream.data.alpaca.markets.
---

# Alpaca Trading API — Quick Reference

The full deep-dive lives in [`docs/alpaca-trading-api-skill.md`](../../../docs/alpaca-trading-api-skill.md). This skill is the **fast-load summary** for in-session work.

## Endpoints (always pin via env)

| Service | URL | Notes |
|---|---|---|
| Paper trading | `https://paper-api.alpaca.markets` | Default for Phase A dev/paper soak |
| Live trading | `https://api.alpaca.markets` | Phase 8+ only — never in CI |
| Market Data REST | `https://data.alpaca.markets` | Same auth as trading |
| Market Data WS — IEX (free) | `wss://stream.data.alpaca.markets/v2/iex` | IEX-only ~3% volume; insufficient for PDH/PDL |
| Market Data WS — SIP (paid) | `wss://stream.data.alpaca.markets/v2/sip` | Consolidated tape; required for live trading |
| Sandbox WS | `wss://stream.data.sandbox.alpaca.markets/v2/{iex,sip}` | Test without live entitlements |

## Authentication

Two HTTP headers on every REST call:

```
APCA-API-KEY-ID: <key>
APCA-API-SECRET-KEY: <secret>
```

WebSocket auth (after socket open):

```json
{"action": "auth", "key": "<key>", "secret": "<secret>"}
```

Wait for `{"T":"success","msg":"authenticated"}` **before** sending subscribe (server may reject premature subscribe).

## Order placement (per `requirements.md` §13)

- **Order type**: market for entry + exit (Phase A)
- **Time-in-force**: `day` (matches 0DTE expiry)
- **Idempotency**: `client_order_id = sha256(tenant_id|trade_id|action)` — Alpaca rejects duplicates
- **Order class**: `simple` for Phase A (no brackets/OCO until later)
- **Sides for options**: `buy_to_open` (entry), `sell_to_close` (exit)

```http
POST /v2/orders
{
  "symbol": "SPY250117C00600000",   // OCC option symbol
  "qty": "1",
  "side": "buy_to_open",
  "type": "market",
  "time_in_force": "day",
  "client_order_id": "OWNER:<trade_id>:ENTRY"
}
```

## OCC option symbol format

```
SPY      250117    C        00600000
^under   ^expiry   ^type    ^strike (×1000, padded to 8 digits)
         (YYMMDD)  C/P      e.g. 600.00 = 00600000
```

Phase 1 strike selector implementation will need to construct this.

## Real-time streams

Subscribe shape (Alpaca uses a JSON object with per-channel symbol arrays):

```json
{
  "action": "subscribe",
  "trades": ["SPY"],
  "quotes": ["SPY"],
  "bars":   ["SPY"]
}
```

Incoming message envelopes use a `T` discriminator field:

| Type | Shape |
|---|---|
| Trade | `{"T":"t","S":"SPY","p":594.23,"s":100,"t":"2026-04-30T13:30:00Z","x":"D"}` |
| Quote | `{"T":"q","S":"SPY","bp":594.20,"bs":100,"ap":594.25,"as":200,"t":"...","bx":"...","ax":"..."}` |
| Bar (1-min agg) | `{"T":"b","S":"SPY","o":594.10,"h":594.50,"l":594.05,"c":594.45,"v":12345,"t":"..."}` |
| Status | `{"T":"success","msg":"connected" \| "authenticated"}` |
| Error | `{"T":"error","code":<int>,"msg":"<reason>"}` |

## EOD position flatten (per req §14)

At 15:55 ET, force-close any open position via market sell. 0DTE risk: leftover positions are auto-exercised at 16:00 if ITM, which can produce unwanted equity assignments. The 5-min cushion is mandatory.

## Failure modes to wire into the Connection FSM

| Alpaca response | FSM action |
|---|---|
| 401 (bad key) | `UNHEALTHY` — alert; manual key rotation |
| 403 (account/entitlement) | `UNHEALTHY` — alert |
| 429 (rate limited) | `DEGRADED` — backoff per `Retry-After` header |
| 422 (validation, e.g., bad `client_order_id`) | log; do not retry; emit signal_evaluated event |
| 5xx | `DEGRADED` then `UNHEALTHY` per Resilience4j thresholds |
| WS `error` event with `code:401` | `UNHEALTHY`; full reconnect with fresh auth |
| WS `error` event with `code:402` (auth required) | race bug — re-auth and resubscribe |

## Anti-patterns to flag in code review

- ❌ Storing API keys in code or config files (must come from Key Vault → env at runtime)
- ❌ Logging full request/response bodies that include secrets
- ❌ Re-using the same `client_order_id` across distinct trades (idempotency violation per `trading-system-guardrails`)
- ❌ Sending WS subscribe before receiving `authenticated` status (race; subscribe may be silently dropped)
- ❌ Hardcoding `paper-api.alpaca.markets` vs `api.alpaca.markets` — must come from env so dev/prod can differ
- ❌ Trusting market-hours-derived timestamps for boundary checks (use `ZoneId.of("America/New_York")` always; see `replay-parity` skill)

## What's NOT here

This skill is the fast-load summary. For full coverage of:
- Options Level 3 trading
- Corporate action handling (assignments, exercises, expirations)
- Account activities API (`/v2/account/activities`)
- Watchlists, calendar, market-status endpoints
- Detailed error code table
- Rate-limit specifics

…see [`docs/alpaca-trading-api-skill.md`](../../../docs/alpaca-trading-api-skill.md).
