# Sentinel replay corpus

Per ADR-0007 §5 + CLAUDE.md guardrail #5. Each fixture pins one
`(SentinelDecisionRequest → recorded Anthropic response → expected saga
decision)` tuple. The replay-parity test in
`com.levelsweep.aiagent.sentinel.replay.SentinelReplayParityTest` injects a
fixture-backed `Fetcher` (no HTTP traffic) and asserts the saga-visible
decision matches the recorded one on every replay-stable field
(`variant + decision_path + fallback_reason + confidence + reason_code +
reason_text`). `clientRequestId` and `latencyMs` are non-deterministic and
explicitly excluded.

## File layout

One JSON per fixture, named after the scenario. Loader sorts by filename, so
fixture order in failure messages is stable.

```
sentinel/replay/
  pdh_long_call_explicit_allow.json
  pdl_long_put_explicit_allow.json
  ...
  rate_limit_fallback.json
  README.md            <- this file
```

## JSON schema

```jsonc
{
  "name": "<scenario>",                  // human label, must match filename stem
  "request": {                           // SentinelDecisionRequest serialized
    "tenant_id": "OWNER",
    "trade_id": "TR_REPLAY_001",
    "signal_id": "SIG_REPLAY_001",
    "direction": "LONG_CALL|LONG_PUT",
    "level_swept": "PDH|PDL|PMH|PML",
    "indicator_snapshot": {
      "ema13": "501.2500",               // BigDecimal as string; scale 4 for prices
      "ema48": "499.4000",
      "ema200": "495.1500",
      "atr14": "1.20",                   // scale 2 for ATR
      "rsi2": "67.50",                   // scale 2 for RSI
      "regime": "BULL",
      "recent_bars": [
        { "ts": "2026-05-04T13:33:00Z", "close": "501.2500", "volume": 11200 }
      ]
    },
    "recent_trades_window": [
      { "trade_id": "TR_PRIOR_001", "outcome": "WIN|LOSS|BE",
        "r_multiple": "1.5", "ts": "2026-05-04T13:00:00Z" }
    ],
    "vix_close_prev": "14.50",
    "now_utc": "2026-05-04T13:35:00Z"    // pinned trading-clock anchor
  },
  "anthropic": {
    "status": 200,                       // 0 = simulate transport failure
    "body": "..."                        // recorded raw 2xx body, OR exception
                                         //   marker when status is 0
  },
  "expected": {
    "type": "Allow|Veto|Fallback",
    "decision_path": "EXPLICIT_ALLOW|LOW_CONFIDENCE_VETO_OVERRIDDEN|FALLBACK_ALLOW",
    "fallback_reason": "TRANSPORT|RATE_LIMIT|COST_CAP|PARSE|TIMEOUT|CB_OPEN",
    "confidence": "0.92",                // BigDecimal as string
    "reason_code": "STRUCTURE_MATCH|...",
    "reason_text": "..."
  }
}
```

## Capturing a new fixture from a real Anthropic call

The Phase 5 paper-soak environment is the source of truth for new fixtures.

1. **Record the prompt + response.** With Sentinel enabled in dev
   (`levelsweep.sentinel.enabled=true`), the audit writer persists every call
   to `audit_log.ai_calls` (compact row) + `audit_log.ai_prompts` (full
   prompt body keyed by SHA-256 hash). Pull both via
   `mongosh` against the dev cluster:

   ```
   db.ai_calls.find({tenant_id: "OWNER", role: "SENTINEL"})
     .sort({occurred_at: -1}).limit(20)
   ```

   Pick the call you want to pin. Note its `prompt_hash`.

2. **Recover the full prompt + response.** The full prompt body lives in
   `ai_prompts` keyed by `prompt_hash`. The full response text is in
   `ai_calls.response_text`.

3. **Build the SentinelDecisionRequest from the trace.** The Decision Engine
   trace (App Insights, `correlation_id` matches saga) carries the
   indicator snapshot + recent trades window + VIX close used to construct
   the prompt. Reconstruct the `request` block from those fields. The
   `now_utc` MUST match the recorded prompt's anchor exactly (down to the
   second; the prompt builder truncates sub-second).

4. **Paste the recorded Anthropic response.** Copy the raw 2xx Anthropic
   body (the envelope, not the inner Sentinel JSON) into `anthropic.body`.
   For failure-mode fixtures, encode as:
   - 4xx/5xx → `status` matches the recorded HTTP status; `body` is the
     recorded error snippet.
   - Transport failure → `status: 0`; `body` is the exception class name +
     message (e.g. `"java.net.ConnectException: Connection refused"`).
   - Circuit breaker open → `status: 0`; `body: "circuit_breaker_open"`.

5. **Fill in `expected`.** The expected saga decision is what a human
   reviewer would have wanted (NOT necessarily what Sentinel said): if
   Sentinel returns `VETO 0.80` the expected outcome is
   `Allow + LOW_CONFIDENCE_VETO_OVERRIDDEN`, NOT `Veto`. Use the parser
   matrix in `SentinelResponseParser` as the reference.

6. **Verify before committing.** Run
   `./gradlew :services:ai-agent-service:test --tests
   "com.levelsweep.aiagent.sentinel.replay.SentinelReplayParityTest"`. Both
   the parameterized per-fixture test AND the corpus-wide parity assertion
   must pass.

## Hash check

The replay test asserts each fixture's `(model, system_prompt, user_message)`
triple hashes to a unique digest via `PromptHasher.hash(...)`. Two fixtures
that share a hash collide on the lookup key and one will silently shadow the
other. Vary `trade_id` or `signal_id` to keep the hashes distinct (the test
fails loudly if a collision lands).

## Why no real HTTP traffic

The replay harness must run offline against historical data. ADR-0007 §5
mandates a fixture-backed `Fetcher`: the recorded body is replayed
byte-for-byte without an Anthropic round-trip. This keeps replay parity
testable in CI without an Anthropic key, and ensures determinism even when
Anthropic ships a model update.
