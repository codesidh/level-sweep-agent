# =============================================================================
# Phase 1 alerts — Application Insights / Azure Monitor.
#
# All five alerts are scheduled-query (KQL) alerts against the
# customMetrics / customEvents tables that the App Insights Java agent
# (3.7.x) populates from Micrometer meters registered by
# `services/market-data-service/.../observability/MetricsBinding.java`.
#
# Naming convention: customMetrics rows have name == <meter-name with dots
# replaced by underscores>. The agent maps Micrometer Counters to
# customMetrics with `value` == cumulative count and `valueCount` == sample
# count; gauges land with `value` == current sample.
#
# A single Action Group (`ag-${project}-${environment}-phase1`) targets the
# Phase 1 operator email. SMS / Twilio webhook is intentionally deferred
# to Phase 7 (full incident-response stack) — see TODO below.
#
# Alert evaluation begins at deploy time; until the first 5-minute window
# closes, alerts will sit in Insufficient Data state. That is expected.
# =============================================================================

resource "azurerm_monitor_action_group" "phase1" {
  name                = "ag-${var.project}-${var.environment}-phase1"
  resource_group_name = azurerm_resource_group.obs.name
  short_name          = "p1ops" # Azure caps short_name at 12 chars

  dynamic "email_receiver" {
    # Empty string == operator hasn't filled in alert_email yet — silently
    # skip the receiver so `terraform apply` doesn't barf. The action group
    # still exists and can be wired to additional receivers later.
    for_each = var.alert_email == "" ? [] : [var.alert_email]
    content {
      name                    = "phase1-operator"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }

  # TODO: add Twilio webhook in Phase 7 for SMS paging on P1+ alerts.
  # Phase 1 ships email-only.

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 1. Alpaca WS circuit breaker open (P1)
# -----------------------------------------------------------------------------
# ConnectionState ordinals (com.levelsweep.marketdata.connection.ConnectionState):
#   0=HEALTHY, 1=DEGRADED, 2=UNHEALTHY, 3=RECOVERING.
# Fires when the alpaca-ws dependency stays at value == 2 for the full
# 5-minute evaluation window.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "alpaca_ws_cb_open" {
  name                = "alert-${var.project}-${var.environment}-alpaca-ws-cb-open"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 1 # Sev 1 == P1
  enabled              = true

  description             = "Alpaca WebSocket circuit breaker is OPEN — hot path is fail-closed and no ticks are being absorbed."
  display_name            = "P1 — Alpaca WS CB open"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "connection_state"
      | where customDimensions.dependency == "alpaca-ws"
      | where value == 2
      | summarize unhealthy_samples = count() by bin(timestamp, 1m)
      | where unhealthy_samples > 0
    KQL
    time_aggregation_method = "Count"
    threshold               = 4 # 5 1-minute bins, allow 1 missed sample window
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 2. Hot-path bar emit P99 > 500ms (P2)
# -----------------------------------------------------------------------------
# Micrometer Timer "bar.emit.duration" → customMetrics with name
# "bar_emit_duration". The agent emits sample buckets the percentiles can be
# computed against. Threshold is in seconds (Micrometer Timer base unit).
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "bar_emit_p99" {
  name                = "alert-${var.project}-${var.environment}-bar-emit-p99"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2 # Sev 2 == P2
  enabled              = true

  description             = "Hot-path bar emit P99 latency exceeded 500ms over the last 5 minutes."
  display_name            = "P2 — Bar emit P99 > 500ms"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "bar_emit_duration"
      | summarize p99 = percentile(value, 99) by bin(timestamp, 5m)
      | where p99 > 0.5
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 3. Bars stalled during RTH (P1)
# -----------------------------------------------------------------------------
# bar.emitted.total Counter — agent emits incremental delta + cumulative total
# as customMetrics rows. We look at delta-style sum() per minute. A 5-minute
# window with zero bars during RTH (13:30-20:00 UTC, M-F) is a hard fail.
# Holiday calendar is NOT modeled in KQL — see runbook for "first triage
# step is confirm trading day".
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "bars_stalled_rth" {
  name                = "alert-${var.project}-${var.environment}-bars-stalled-rth"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT10M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 1
  enabled              = true

  description             = "No bars emitted in the last 10 minutes during RTH (13:30-20:00 UTC, M-F). Likely WS disconnect or aggregator hang."
  display_name            = "P1 — Bars stalled during RTH"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      let isRTH = (t: datetime) {
          let dow = dayofweek(t);
          let h = datetime_part("hour", t);
          let m = datetime_part("minute", t);
          let mins_utc = h * 60 + m;
          dow != 0d and dow != 6d and mins_utc >= 810 and mins_utc < 1200
      };
      customMetrics
      | where name == "bar_emitted_total"
      | where isRTH(timestamp)
      | summarize emits = sum(valueCount) by bin(timestamp, 5m)
      | where emits == 0
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 4. Mongo CB open (P3)
# -----------------------------------------------------------------------------
# Phase 1 doesn't yet wire a ConnectionMonitor for Mongo (the Quarkus driver
# manages its own pool + retry). The App Insights Java agent auto-instruments
# Mongo and emits failed-call telemetry as `dependencies` rows. We alert on
# sustained failures rather than on a Connection FSM state, so this rule
# reads `dependencies` directly. Phase 7 wires a real Mongo
# ConnectionMonitor at which point this rule moves to the customMetrics
# pattern used by alerts 1 and 4.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "mongo_cb_open" {
  name                = "alert-${var.project}-${var.environment}-mongo-cb-open"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3 # Sev 3 == P3 (bar persistence is the only Mongo path in Phase 1; bars still flow to Kafka)
  enabled              = true

  description             = "Mongo dependency calls failing > 50% over the last 15 minutes — bar persistence at risk."
  display_name            = "P3 — Mongo dependency failure rate elevated"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      dependencies
      | where type == "MongoDB" or target startswith "mongo"
      | summarize total = count(), failed = countif(success == false) by bin(timestamp, 5m)
      | where total > 0
      | extend fail_pct = 100.0 * failed / total
      | where fail_pct > 50
    KQL
    time_aggregation_method = "Count"
    threshold               = 2 # 2 of 3 5-minute bins
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 5. TickRingBuffer drop rate > 1000/min (P1)
# -----------------------------------------------------------------------------
# tick.dropped.total is a FunctionCounter exposing the cumulative drop count.
# The agent emits delta + cumulative; we use delta-style sum() per minute.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "tick_drop_rate" {
  name                = "alert-${var.project}-${var.environment}-tick-drop-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 1
  enabled              = true

  description             = "TickRingBuffer dropping > 1000 ticks/minute — drainer can't keep up. Likely Mongo / Kafka backpressure cascading into the hot path."
  display_name            = "P1 — TickRingBuffer drop rate > 1000/min"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "tick_dropped_total"
      | summarize delta = max(value) - min(value) by bin(timestamp, 1m)
      | where delta > 1000
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# =============================================================================
# Phase 3 alerts — Execution Service (Alpaca paper trading).
#
# Cumulative on top of the Phase 1 set; per architecture-spec §21.1 the Phase 3
# entry-gate soak is against the live Alpaca options + execution stack, so the
# new alerts cover (a) Alpaca connection health, (b) order-fill timeouts on the
# happy path, (c) end-of-day flatten failures (real-money risk via 0DTE auto-
# exercise at 16:00 ET), and (d) NBBO snapshot staleness for the trail manager.
#
# All four reuse the Phase 1 action group — Phase 7 splits Sev 1 paging onto a
# separate group with Twilio SMS. Until then, email-only.
# =============================================================================

# -----------------------------------------------------------------------------
# 6. Alpaca CB UNHEALTHY (P1)
# -----------------------------------------------------------------------------
# ConnectionState ordinals (com.levelsweep.marketdata.connection.ConnectionState):
#   0=HEALTHY, 1=DEGRADED, 2=UNHEALTHY, 3=RECOVERING.
# Phase 3 introduces two new Connection FSMs in execution-service:
#   - alpaca-rest               (order placement, position queries)
#   - alpaca-trade-updates-ws   (fill notifications)
# When either reaches UNHEALTHY for >= 3 consecutive samples, Risk FSM auto-
# HALTs new entries (CLAUDE.md guardrail #3 fail-closed). 1-minute bins, so
# 3 consecutive unhealthy samples == 3 minutes; we use a 5-minute window with
# threshold 3 to require sustained unhealthy.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "alpaca_cb_unhealthy" {
  name                = "alert-${var.project}-${var.environment}-alpaca-cb-unhealthy"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 1 # Sev 1 == P1 — Risk FSM HALTs new entries on this signal
  enabled              = true

  description             = "Alpaca REST or trade-updates WS connection FSM is UNHEALTHY for 3+ consecutive samples. Risk FSM has auto-HALTed new entries (fail-closed). Existing positions continue under deterministic exit rules."
  display_name            = "P1 — Alpaca CB UNHEALTHY (rest|trade-updates-ws)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "connection_state"
      | where customDimensions.dependency in ("alpaca-rest", "alpaca-trade-updates-ws")
      | where value >= 2
      | summarize unhealthy_samples = count() by bin(timestamp, 1m), tostring(customDimensions.dependency)
      | where unhealthy_samples > 0
    KQL
    time_aggregation_method = "Count"
    threshold               = 3 # 3 consecutive 1-minute bins unhealthy across either dependency
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 7. Order fill timeout (P2)
# -----------------------------------------------------------------------------
# Saga step 5 (place + await fill) emits the log line "order fill timeout"
# from its compensation path when a TradeOrderSubmitted has no matching
# TradeFilled within 30 seconds. We use the simpler log-pattern KQL here
# rather than the FSM-correlated count(submitted) - count(filled) approach;
# the saga compensation log is the single source of truth and is already
# emitted with cloud_RoleName == "execution-service".
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "order_fill_timeout" {
  name                = "alert-${var.project}-${var.environment}-order-fill-timeout"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2 # Sev 2 == P2 — saga compensates; no real-money loss but we missed an entry
  enabled              = true

  description             = "Saga step 5 logged 'order fill timeout' — a TradeOrderSubmitted had no matching TradeFilled within 30 seconds. Compensation has cancelled the order; investigate Alpaca latency or NBBO drift."
  display_name            = "P2 — Order fill timeout"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      traces
      | where cloud_RoleName == "execution-service"
      | where message contains "order fill timeout"
      | summarize timeouts = count() by bin(timestamp, 5m)
      | where timeouts > 0
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 8. EOD flatten failure (P1)
# -----------------------------------------------------------------------------
# The 15:55 ET cron in execution-service walks all open positions and emits
# a TradeExitRequested per position; each result is written to the
# eod_flatten_attempts audit table. A row with outcome = 'FAILED' indicates
# the flatten did not exit a position before 16:00 ET — at which point 0DTE
# SPY options auto-exercise. That's real money even on Alpaca paper.
#
# This rule fires off the corresponding log line from the cron's per-trade
# loop ("eod flatten: trade FAILED"). The complementary "EOD flatten missed"
# alert (no audit row at all) is intentionally NOT in this PR — it requires
# a synthetic heartbeat, see TODO below.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "eod_flatten_failure" {
  name                = "alert-${var.project}-${var.environment}-eod-flatten-failure"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 1 # Sev 1 == P1 — 0DTE auto-exercise at 16:00 ET is real money even on paper
  enabled              = true

  description             = "EOD flatten cron logged 'eod flatten: trade FAILED' — at least one open position did not exit before 16:00 ET. 0DTE auto-exercise risk. Operator: page broker desk and reconcile manually. The 'cron didn't fire AT ALL' P0 escalation gap is covered by alert #18 (eod_flatten_heartbeat_missed)."
  display_name            = "P1 — EOD flatten failure"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      traces
      | where cloud_RoleName == "execution-service"
      | where message contains "eod flatten: trade FAILED"
      | summarize failures = count() by bin(timestamp, 5m)
      | where failures > 0
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 9. Trail manager NBBO snapshot stale (P3)
# -----------------------------------------------------------------------------
# AlpacaQuotesClient polls NBBO every few seconds for the trail manager. On
# poll failure it logs "trail.poll.stale" with the trade-id. Stale NBBO does
# NOT trigger an exit (fail-closed for the exit path — we only exit on a
# fresh quote crossing the trail). This alert is informational; chronic
# staleness suggests Alpaca quote-feed throttling or auth drift.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "trail_nbbo_stale" {
  name                = "alert-${var.project}-${var.environment}-trail-nbbo-stale"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3 # Sev 3 == P3 — informational; stale NBBO does not exit the trade
  enabled              = true

  description             = "AlpacaQuotesClient logged 'trail.poll.stale' more than 3 times in a 30-second bin. Trail manager is operating on stale NBBO — exits are fail-closed (no exit on stale quote). Investigate Alpaca quote-feed throttling or auth drift."
  display_name            = "P3 — Trail manager NBBO stale"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      traces
      | where cloud_RoleName == "execution-service"
      | where message contains "trail.poll.stale"
      | summarize stale_polls = count() by bin(timestamp, 30s)
      | where stale_polls > 3
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# =============================================================================
# Phase 4 alerts (alerts 10-13) — AI Agent Service.
#
# Meter names emitted by services/ai-agent-service/.../observability/AiAgentMetrics.java:
#   ai.cost.daily_total_usd          (gauge, tagged tenant_id + role)
#   ai.narrator.skipped              (counter, tagged tenant_id + reason)
#   ai.narrator.fired                (counter, tagged tenant_id)
#   ai.reviewer.run.complete         (counter, tagged tenant_id + outcome)
#
# App Insights converts dots-to-underscores in customMetrics name field.
# All four alerts share the Phase 1 action group; Phase 7 splits onto Twilio
# for higher-severity escalation.
# =============================================================================

# Alert 10: AI daily cost cap approached or exceeded.
# Per-role caps (architecture-spec §4.8): $1/day each for narrator + reviewer
# in Phase 4. 0.9 USD == 90% threshold. Severity 2 — role degrades gracefully
# (skip + log) per architecture-spec §4.9.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "ai_cost_cap_warn" {
  name                = "alert-${var.project}-${var.environment}-ai-cost-cap-warn"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "AI Agent Service spent >= $0.90 (90% of the $1.00 per-(tenant, role) daily cap) in a 1-hour window. Cost cap fully breached at $1.00 — role will degrade to no-op until 00:00 ET. Investigate: runaway loop? misconfigured prompt that's exploding?"
  display_name            = "P2 — AI cost cap approached (>= 90% of role cap)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "ai_cost_daily_total_usd"
      | where cloud_RoleName == "ai-agent-service"
      | summarize peak_cost_usd = max(value) by tostring(customDimensions.tenant_id), tostring(customDimensions.role)
      | where peak_cost_usd >= 0.9
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# Alert 11: Anthropic API Connection FSM UNHEALTHY.
# Phase 5 S1 (PR #116) wires the Connection FSM in ai-agent-service. Flag is
# now enabled. Sentinel falls back to ALLOW on outage per ADR-0007 §3 (cb_open
# fallback path), so this alert is informational/operational rather than a
# trade-halt signal — the deterministic Risk FSM HALT remains the fail-closed
# entry path. Severity stays at 2 because the AI veto layer is degraded but
# the trading saga continues.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "anthropic_cb_unhealthy" {
  name                = "alert-${var.project}-${var.environment}-anthropic-cb-unhealthy"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT1M"
  window_duration      = "PT5M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "AI Agent Service's Connection FSM for Anthropic API is UNHEALTHY (state >= 2) for 3+ consecutive 1-minute samples. Sentinel/Narrator/Reviewer fall back to ALLOW (Sentinel) / skip (Narrator/Reviewer) per ADR-0007 §3. Existing positions continue under deterministic exit rules; the deterministic Risk FSM HALT remains the fail-closed entry path."
  display_name            = "P2 — Anthropic API CB UNHEALTHY"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "connection_state"
      | where customDimensions.dependency == "anthropic"
      | where cloud_RoleName == "ai-agent-service"
      | where value >= 2
      | summarize unhealthy_samples = count() by bin(timestamp, 1m)
      | where unhealthy_samples >= 3
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# Alert 12: Trade Narrator skip rate elevated.
# Skip ratio = skipped / (skipped + fired) over a 1-hour window with at least
# 4 total events. > 50% means something is consistently going wrong:
# persistent cost cap, repeated 429/529 from Anthropic, mongo down, or a
# prompt regression. Severity 3 because narratives are non-critical.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "narrator_skip_rate_elevated" {
  name                = "alert-${var.project}-${var.environment}-narrator-skip-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3
  enabled              = true

  description             = "Narrator skip ratio > 50% over the past hour (with at least 4 total narrator events). Investigate: cost cap breach? Anthropic 429/529? Mongo connectivity? Prompt regression?"
  display_name            = "P3 — Narrator skip rate > 50% (1h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      let skipped = customMetrics
        | where name == "ai_narrator_skipped" and cloud_RoleName == "ai-agent-service"
        | summarize skipped_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      let fired = customMetrics
        | where name == "ai_narrator_fired" and cloud_RoleName == "ai-agent-service"
        | summarize fired_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      skipped
      | join kind=fullouter (fired) on tenant_id
      | extend skipped_count = coalesce(skipped_count, 0L), fired_count = coalesce(fired_count, 0L)
      | extend total = skipped_count + fired_count
      | where total >= 4
      | extend skip_ratio = todouble(skipped_count) / todouble(total)
      | where skip_ratio > 0.5
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# Alert 13: Daily Reviewer didn't run.
# DailyReviewerScheduler fires at 16:30 ET via Quarkus @Scheduled. The cron
# emits one increment per fire on `ai.reviewer.run.complete` regardless of
# whether the review succeeded or was skipped. If no recent rows are seen,
# the cron didn't fire — JVM crashed, scheduler disabled, pod missing.
# Severity 2 because the daily report is missing and operator must reconcile.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "reviewer_run_missing" {
  name                = "alert-${var.project}-${var.environment}-reviewer-run-missing"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT30M"
  window_duration      = "P2D"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "Daily Reviewer scheduler did not emit ai.reviewer.run.complete in the last 26 hours. Cron didn't fire (JVM crash? scheduler disabled? pod missing?). Operator: read ai-agent-service logs for the period and decide whether to re-trigger manually or accept the gap."
  display_name            = "P2 — Daily Reviewer run missing (>26h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customMetrics
      | where name == "ai_reviewer_run_complete" and cloud_RoleName == "ai-agent-service"
      | summarize last_seen = max(timestamp) by tenant_id = tostring(customDimensions.tenant_id)
      | extend hours_since = datetime_diff('hour', now(), last_seen)
      | where hours_since >= 26
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# =============================================================================
# Phase 6 alerts (alerts 14-17) — Cold-path services (Journal / Notification /
# BFF). Adds visibility for the audit, alert delivery, and edge-API tiers
# introduced in Phase 6.
#
# Strimzi (Kafka) and Mongo are NOT yet deployed in dev (Phase 6 follow-up + 7).
# Until they are, alerts that depend on Kafka/Mongo telemetry will sit in
# Insufficient Data — that is the desired state, not a fault. The alerts are
# wired now so that the moment those dependencies land, observability is
# already in place; Phase 7 will additionally provision a synthetic heartbeat
# so "service silently absent" surfaces as a P1 instead of a quiet gap.
#
# All four reuse the Phase 1 action group; Phase 7 splits high-severity
# escalation onto a Twilio-backed group.
# =============================================================================

# -----------------------------------------------------------------------------
# 14. BFF 5xx error rate elevated (P2)
# -----------------------------------------------------------------------------
# api-gateway-bff is the user-facing edge — sustained 5xx means the operator
# UI / external API consumers are seeing failures. The Spring Boot app
# auto-instruments HTTP requests via the App Insights agent, which lands in
# the `requests` table with success == false on 5xx.
# Threshold 5% over a 15-minute window with at least 20 requests; lower
# baselines are noise from one-off errors. Severity 2 — operator action
# required but no real-money risk.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "bff_5xx_rate" {
  name                = "alert-${var.project}-${var.environment}-bff-5xx-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "api-gateway-bff is returning 5xx for > 5% of requests over the last 15 minutes (with at least 20 requests in window). Operator UI and external API consumers are degraded. Investigate: downstream service down? config drift? auth provider outage?"
  display_name            = "P2 — BFF 5xx error rate > 5% (15m)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      requests
      | where cloud_RoleName == "api-gateway-bff"
      | summarize total = count(), failed = countif(toint(resultCode) >= 500) by bin(timestamp, 5m)
      | where total >= 20
      | extend fail_pct = 100.0 * failed / total
      | where fail_pct > 5
    KQL
    time_aggregation_method = "Count"
    threshold               = 2 # 2 of 3 5-minute bins
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 15. Journal Mongo write failure rate (P2 — DISABLED until Mongo lands)
# -----------------------------------------------------------------------------
# journal-service is the audit-of-record (architecture-spec §6: CP for write).
# The App Insights Java agent auto-instruments Mongo and emits per-call rows
# in `dependencies`. A sustained > 30% failure rate means audit data is
# silently dropping — Phase 7 adds 17a-4 WORM compliance which makes a
# missing audit row a P1, but Phase 6 (paper) treats it as P2.
# DISABLED until in-cluster Mongo is deployed (Phase 6 follow-up). Flip
# enabled = true alongside the Mongo Helm release.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "journal_mongo_write_failures" {
  name                = "alert-${var.project}-${var.environment}-journal-mongo-write-failures"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT15M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = false

  description             = "journal-service Mongo write failure rate > 30% over the last 15 minutes — audit data is dropping silently. Phase 7 escalates to P1 once 17a-4 WORM retention is gated on this stream. DISABLED until in-cluster Mongo is deployed."
  display_name            = "P2 — Journal Mongo write failures > 30% (15m, DISABLED until Mongo in cluster)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      dependencies
      | where cloud_RoleName == "journal-service"
      | where type == "MongoDB" or target startswith "mongo"
      | summarize total = count(), failed = countif(success == false) by bin(timestamp, 5m)
      | where total > 0
      | extend fail_pct = 100.0 * failed / total
      | where fail_pct > 30
    KQL
    time_aggregation_method = "Count"
    threshold               = 2
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 16. Notification dispatch failure rate (P3)
# -----------------------------------------------------------------------------
# notification-service writes a row to notifications.outbox per delivery
# attempt with status in (SENT, SKIPPED, FAILED). EmailDispatcher logs the
# message "notification dispatch failed" with the recipient + reason on the
# FAILED branch. We aggregate that log line over a 1-hour window — this
# catches both transient SMTP outages and chronic config drift.
#
# Severity 3 because alerts are eventually-delivered (architecture-spec §6 AP)
# and the outbox row enables manual replay. Severity escalates to P1 once
# Twilio SMS is wired in Phase 7 (a missed P1 trade alert IS real-money risk).
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "notification_dispatch_failures" {
  name                = "alert-${var.project}-${var.environment}-notification-dispatch-failures"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3
  enabled              = true

  description             = "notification-service logged 'notification dispatch failed' more than 5 times in the last hour. Outbox rows record FAILED — manual replay possible. Investigate SMTP relay availability, credentials drift, or rate-limit. Phase 7 escalates to P1 once Twilio SMS lands."
  display_name            = "P3 — Notification dispatch failures > 5 (1h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      traces
      | where cloud_RoleName == "notification-service"
      | where message contains "notification dispatch failed"
      | summarize failures = count() by bin(timestamp, 15m)
      | where failures > 5
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 17. Pod restart storm (P2)
# -----------------------------------------------------------------------------
# The App Insights Java agent emits a "ContainerStarted" customEvent on every
# JVM boot. A pod restarting more than 3 times in a 30-minute window is in
# CrashLoopBackOff or worse. This catches the Phase 6 class of failures (Kafka
# bootstrap unresolvable, Mongo unreachable, Flyway against a missing MS SQL)
# that PR #109 specifically solves — once that PR is in place, this alert
# serves as a regression detector for the same class of issue.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "pod_restart_storm" {
  name                = "alert-${var.project}-${var.environment}-pod-restart-storm"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT5M"
  window_duration      = "PT30M"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "A LevelSweep pod restarted more than 3 times in the last 30 minutes — likely CrashLoopBackOff. Correlate with the failing service's traces. Recurring offenders in Phase 6: Kafka bootstrap unresolvable, Mongo unreachable, Flyway against a missing MS SQL."
  display_name            = "P2 — Pod restart storm (>3 restarts/30m)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      customEvents
      | where name == "ContainerStarted"
      | extend role = tostring(cloud_RoleName)
      | where role != ""
      | summarize starts = count() by role, bin(timestamp, 30m)
      | where starts > 3
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 18. EOD flatten heartbeat missed (P0)
# -----------------------------------------------------------------------------
# The complement to alert #8 (eod_flatten_failure). #8 fires only when the
# cron ran and a trade FAILED. If the cron didn't fire AT ALL — JVM crash,
# pod missing, scheduler disabled — no row exists, so #8 is silent. This
# alert catches that gap.
#
# EodFlattenScheduler emits exactly one of these log lines per fire:
#   "eod scheduler running in stub mode" (no OrderSubmitter bean)
#   "eod flatten: no in-flight trades; skipping" (no positions)
#   "eod flatten: starting sessionDate=..." (has positions)
# We aggregate across all three. Window = 26 hours (one daily fire + 2-hour
# tolerance for daylight savings + cron drift). Severity 0 because 0DTE
# auto-exercise at 16:00 ET is real money even on Alpaca paper — a missed
# cron means open positions auto-exercise at the strike price with no exit
# attempt logged.
#
# Phase 8 (paper→live) bumps the severity 0 to a Twilio SMS page; Phase 6
# email-only is the same as the rest of the action group.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "eod_flatten_heartbeat_missed" {
  name                = "alert-${var.project}-${var.environment}-eod-flatten-heartbeat-missed"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT30M"
  window_duration      = "P2D"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 0 # Sev 0 == P0 — 0DTE auto-exercise at 16:00 ET is real money
  enabled              = true

  description             = "EodFlattenScheduler did not emit a 'eod flatten: ...' or 'eod scheduler running in stub mode' log line in the last 26 hours. Cron didn't fire — open 0DTE positions WILL auto-exercise at 16:00 ET strike. Operator: page broker desk, manually flatten any open positions via Alpaca dashboard, investigate scheduler health (JVM crash? pod missing? Quartz disabled?)."
  display_name            = "P0 — EOD flatten heartbeat missed (>26h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      traces
      | where cloud_RoleName == "execution-service"
      | where message has_any (
          "eod flatten: starting",
          "eod flatten: no in-flight trades",
          "eod scheduler running in stub mode")
      | summarize last_seen = max(timestamp) by tenant_id = "OWNER"
      | extend hours_since = datetime_diff('hour', now(), last_seen)
      | where hours_since >= 26
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# =============================================================================
# Phase 5 alerts (alerts 19-21) — Sentinel + Conversational Assistant.
#
# Counter taxonomy emitted by services/ai-agent-service per ADR-0007 §3:
#   ai.sentinel.allow{tenant_id, level_swept, decision_path}
#   ai.sentinel.veto_applied{tenant_id, level_swept}
#   ai.sentinel.fallback{tenant_id, reason}
#   ai.sentinel.skipped{tenant_id, reason="flag_off"}
#   ai.assistant.fired{tenant_id}
#   ai.assistant.failed{tenant_id, reason}
#
# All three reuse the Phase 1 action group; Phase 7 splits high-severity
# escalation onto a Twilio-backed group.
# =============================================================================

# -----------------------------------------------------------------------------
# 19. Sentinel veto rate elevated (P3, informational)
# -----------------------------------------------------------------------------
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "sentinel_veto_rate" {
  name                = "alert-${var.project}-${var.environment}-sentinel-veto-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3
  enabled              = true

  description             = "Sentinel veto ratio > 30% of trade attempts in the last hour (with at least 10 total attempts). Investigate: strategy parameter drift? unusual market regime? Capture replay fixtures of the vetoed signals for regression analysis (see services/ai-agent-service/src/test/resources/sentinel/replay/README.md)."
  display_name            = "P3 — Sentinel veto rate > 30% (1h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      let veto = customMetrics
        | where name == "ai_sentinel_veto_applied" and cloud_RoleName == "ai-agent-service"
        | summarize veto_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      let allow = customMetrics
        | where name == "ai_sentinel_allow" and cloud_RoleName == "ai-agent-service"
        | summarize allow_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      veto
      | join kind=fullouter (allow) on tenant_id
      | extend veto_count = coalesce(veto_count, 0L), allow_count = coalesce(allow_count, 0L)
      | extend total = veto_count + allow_count
      | where total >= 10
      | extend veto_ratio = todouble(veto_count) / todouble(total)
      | where veto_ratio > 0.3
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 20. Sentinel fallback rate elevated (P2)
# -----------------------------------------------------------------------------
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "sentinel_fallback_rate" {
  name                = "alert-${var.project}-${var.environment}-sentinel-fallback-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 2
  enabled              = true

  description             = "Sentinel fallback ratio > 20% over the past hour (cb_open / timeout / cost_cap / parse / transport / rate_limit). Each fallback degrades to ALLOW per ADR-0007 §3 — the AI veto layer is not running. Investigate: Anthropic outage (alert #11)? Cost cap breach (alert #10)? Sustained rate limiting? Sentinel system prompt regression (parse failures)?"
  display_name            = "P2 — Sentinel fallback rate > 20% (1h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      let fallback = customMetrics
        | where name == "ai_sentinel_fallback" and cloud_RoleName == "ai-agent-service"
        | summarize fallback_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      let allow = customMetrics
        | where name == "ai_sentinel_allow" and cloud_RoleName == "ai-agent-service"
        | summarize allow_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      let veto = customMetrics
        | where name == "ai_sentinel_veto_applied" and cloud_RoleName == "ai-agent-service"
        | summarize veto_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      fallback
      | join kind=fullouter (allow) on tenant_id
      | join kind=fullouter (veto) on tenant_id
      | extend fallback_count = coalesce(fallback_count, 0L), allow_count = coalesce(allow_count, 0L), veto_count = coalesce(veto_count, 0L)
      | extend total = fallback_count + allow_count + veto_count
      | where total >= 10
      | extend fallback_ratio = todouble(fallback_count) / todouble(total)
      | where fallback_ratio > 0.2
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}

# -----------------------------------------------------------------------------
# 21. Conversational Assistant failure rate elevated (P3)
# -----------------------------------------------------------------------------
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "assistant_failure_rate" {
  name                = "alert-${var.project}-${var.environment}-assistant-failure-rate"
  resource_group_name = azurerm_resource_group.obs.name
  location            = azurerm_resource_group.obs.location

  evaluation_frequency = "PT15M"
  window_duration      = "PT1H"
  scopes               = [azurerm_application_insights.main.id]
  severity             = 3
  enabled              = true

  description             = "Conversational Assistant failure ratio > 50% over the past hour (with at least 4 total events). Investigate: cost cap breach (alert #10)? Anthropic 429/529? Mongo connectivity? Operator UX is degraded but trading is unaffected (Assistant is not on the saga hot path)."
  display_name            = "P3 — Assistant failure rate > 50% (1h)"
  auto_mitigation_enabled = true

  criteria {
    query                   = <<-KQL
      let failed = customMetrics
        | where name == "ai_assistant_failed" and cloud_RoleName == "ai-agent-service"
        | summarize failed_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      let fired = customMetrics
        | where name == "ai_assistant_fired" and cloud_RoleName == "ai-agent-service"
        | summarize fired_count = sum(valueCount) by tenant_id = tostring(customDimensions.tenant_id);
      failed
      | join kind=fullouter (fired) on tenant_id
      | extend failed_count = coalesce(failed_count, 0L), fired_count = coalesce(fired_count, 0L)
      | extend total = failed_count + fired_count
      | where total >= 4
      | extend fail_ratio = todouble(failed_count) / todouble(total)
      | where fail_ratio > 0.5
    KQL
    time_aggregation_method = "Count"
    threshold               = 1
    operator                = "GreaterThanOrEqual"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  action {
    action_groups = [azurerm_monitor_action_group.phase1.id]
  }

  tags = var.tags
}
