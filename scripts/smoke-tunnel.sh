#!/usr/bin/env bash
# =============================================================================
# smoke-tunnel.sh — exercise every BFF endpoint via the public Cloudflare
# tunnel and report ✅ PASS / ⚠️  DEGRADED-EXPECTED / ❌ FAIL per route.
#
# Usage:
#   scripts/smoke-tunnel.sh                       # uses default tunnel URL
#   BASE=https://<other> scripts/smoke-tunnel.sh  # override the host
#   TENANT=OWNER scripts/smoke-tunnel.sh          # override tenant (Phase A
#                                                 # still only supports OWNER)
#
# Exit status: 0 if every endpoint is PASS or DEGRADED-EXPECTED, 1 if any
# endpoint is FAIL. CI / cron jobs that want a hard check should treat the
# exit code as the contract.
#
# Why a shell script and not a JUnit / Playwright suite: this needs to run
# against the deployed cluster (not a Quarkus DevServices in-process), and
# it has to work from any operator laptop without Node / browser deps. A
# 200-line bash file is the smallest reliable surface for a smoke check.
# =============================================================================
set -u

BASE="${BASE:-https://dolls-employ-envelope-magnificent.trycloudflare.com}"
TENANT="${TENANT:-OWNER}"

# Counters.
pass=0
degraded=0
fail=0

# ANSI colour helpers — no-op on non-TTY so CI logs stay clean.
if [[ -t 1 ]]; then
  GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; CYAN=$'\033[36m'; RESET=$'\033[0m'
else
  GREEN=""; YELLOW=""; RED=""; CYAN=""; RESET=""
fi

# probe METHOD PATH EXPECTED-CODES NOTES [BODY]
#   EXPECTED-CODES is a CSV: "200" or "200,404" — first hit wins.
#   If the actual code matches, mark PASS.
#   If actual code is one of the DEGRADED codes (4th arg with prefix DEGRADED:),
#   mark DEGRADED-EXPECTED with the explanatory note.
#   Else FAIL with the body snippet.
probe() {
  local method="$1"
  local path="$2"
  local expect="$3"     # CSV of acceptable codes, e.g. "200" or "200,404"
  local note="$4"       # one-line summary
  local body="${5:-}"   # JSON body for POST; empty otherwise
  local degraded_codes="${6:-}"  # CSV of codes that are tolerable-degraded

  local url="${BASE}${path}"
  local tmp; tmp=$(mktemp)
  local code
  if [[ "$method" == "POST" ]]; then
    code=$(curl -sS -o "$tmp" -w "%{http_code}" --max-time 30 \
      -X POST -H "X-Tenant-Id: ${TENANT}" -H "Content-Type: application/json" \
      -d "$body" "$url" 2>/dev/null || echo "000")
  else
    code=$(curl -sS -o "$tmp" -w "%{http_code}" --max-time 30 \
      -H "X-Tenant-Id: ${TENANT}" "$url" 2>/dev/null || echo "000")
  fi

  local label
  if [[ ",${expect}," == *",${code},"* ]]; then
    label="${GREEN}✅ PASS${RESET}"
    pass=$((pass+1))
  elif [[ -n "${degraded_codes}" && ",${degraded_codes}," == *",${code},"* ]]; then
    label="${YELLOW}⚠️  DEGRADED-EXPECTED${RESET}"
    degraded=$((degraded+1))
  else
    label="${RED}❌ FAIL${RESET}"
    fail=$((fail+1))
  fi

  printf "  %-26s %s %-3s %s\n" "${method} ${path}" "${label}" "${code}" "${CYAN}${note}${RESET}"
  if [[ "$label" == *FAIL* ]]; then
    # Show the first 200 chars of the response body to aid debugging.
    local snippet; snippet=$(head -c 200 "$tmp" | tr -d '\n')
    printf "    %s└─ body: %s%s\n" "${RED}" "${snippet}" "${RESET}"
  fi
  rm -f "$tmp"
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------
echo "${CYAN}LevelSweep BFF smoke test${RESET}"
echo "  base   = ${BASE}"
echo "  tenant = ${TENANT}"
echo

echo "${CYAN}── Edge / health ───────────────────────────────────────────────────${RESET}"
probe GET  "/api/health"  "200"  "BFF liveness"

echo
echo "${CYAN}── Dashboard aggregator ────────────────────────────────────────────${RESET}"
# DashboardController returns 200 with degraded=true when sub-fetches fail
# (config service intentionally off, projection has no data yet).
probe GET  "/api/dashboard/${TENANT}/summary"  "200"  "fan-out: journal+config+projection+calendar"

echo
echo "${CYAN}── Calendar service ────────────────────────────────────────────────${RESET}"
probe GET  "/api/calendar/today"  "200"  "today's market session info"
# These two are referenced by the frontend but BFF doesn't proxy them yet.
probe GET  "/api/calendar/blackout-dates"  "200"  "blackout dates list" "" "404,405"
probe GET  "/api/calendar/2026-05-05"  "200"  "specific date info" "" "404,405"

echo
echo "${CYAN}── Journal / audit aggregator ─────────────────────────────────────${RESET}"
probe GET  "/api/journal/${TENANT}"  "200"  "trade events list (may be empty)"

echo
echo "${CYAN}── User-config service ─────────────────────────────────────────────${RESET}"
# user-config-service is intentionally replicas=0 in dev (chart comment notes
# Flyway needs MS SQL which isn't deployed — Phase 7 brings Azure SQL).
# Connection-refused → BFF should bubble a 5xx; mark as expected-degraded.
probe GET  "/api/config/${TENANT}"  "200"  "feature flags + risk params" "" "500,502,503,504"

echo
echo "${CYAN}── Projection service ──────────────────────────────────────────────${RESET}"
# 404 is expected when no projection has been run for this tenant yet.
probe GET  "/api/projection/${TENANT}/last"  "200"  "latest cached run" "" "404"
# The POST /run path was 415 until PR #138 fixed BFF body forwarding.
probe POST "/api/projection/${TENANT}/run"  "200"  "Monte Carlo recompute" \
  '{"tenantId":"OWNER","accountSize":100000.0,"maxRiskPerTradeBps":50,"winRate":0.55,"avgWinR":2.0,"avgLossR":1.0,"tradesPerDay":3,"sessions":20,"simulations":1000}'

echo
echo "${CYAN}── AI Agent — Conversational Assistant ─────────────────────────────${RESET}"
probe POST "/api/v1/assistant/chat"  "200"  "Anthropic Sonnet round-trip" \
  '{"tenantId":"OWNER","userMessage":"Reply with one short sentence to confirm you are responding."}'
probe GET  "/api/v1/assistant/conversations?tenantId=${TENANT}&limit=20"  "200"  "list conversations"

echo
echo "${CYAN}── Summary ─────────────────────────────────────────────────────────${RESET}"
total=$((pass + degraded + fail))
printf "  %d total · ${GREEN}%d pass${RESET} · ${YELLOW}%d degraded-expected${RESET} · ${RED}%d fail${RESET}\n" \
  "$total" "$pass" "$degraded" "$fail"

if (( fail > 0 )); then
  echo "  ${RED}smoke test FAILED — at least one endpoint is unexpectedly broken${RESET}"
  exit 1
fi
echo "  ${GREEN}smoke test passed${RESET}"
exit 0
