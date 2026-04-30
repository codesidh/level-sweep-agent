## Summary

<!-- What does this PR change? Why? Reference ADR / issue if applicable. -->

## Type
- [ ] feat — new functionality
- [ ] fix — bug fix
- [ ] refactor — code change without behavior change
- [ ] docs — documentation only
- [ ] test — test only
- [ ] chore — tooling, build, deps

## Affected Modules
- [ ] market-data-service
- [ ] decision-engine (signal / risk / strike / saga)
- [ ] execution-service
- [ ] ai-agent-service (sentinel / narrator / assistant / reviewer)
- [ ] journal-service
- [ ] cold-path service: ___
- [ ] frontend
- [ ] infra (terraform / k8s)

## Trading Discipline Checklist
- [ ] **Replay parity**: `./gradlew replayTest` green at ≥99% (required for any decision-engine change)
- [ ] **Idempotency**: external calls have deterministic keys
- [ ] **Tenant scoping**: queries / publishes / tools include `tenant_id`
- [ ] **Audit trail**: state changes write to `fsm_transitions`
- [ ] **No real money**: tests use Alpaca paper or mocks
- [ ] **No credential logs**: secret values redacted in logs

## AI Discipline Checklist (if PR touches AI code)
- [ ] Prompt changes versioned in `prompts/` and hash logged
- [ ] Cost cap enforced in code (not just monitoring)
- [ ] Tool-use protocol (structured); no free-text parsing for control flow
- [ ] Fail-open behavior tested (timeout, 5xx, malformed JSON)
- [ ] No new tools that mutate orders / FSM state

## Phase B Check (if PR adds Phase B functionality)
- [ ] Behind feature flag (default OFF)
- [ ] Documented in `docs/feature-flags.md`
- [ ] Inventory updated in `phase-a-b-feature-flags` skill

## Tests
- [ ] Unit tests added / updated
- [ ] Integration tests added / updated (if cross-service)
- [ ] Replay test added (if new edge case discovered)

## ADR
- [ ] Architectural decision: ADR added under `adr/`
- [ ] N/A
