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

  description             = "EOD flatten cron logged 'eod flatten: trade FAILED' — at least one open position did not exit before 16:00 ET. 0DTE auto-exercise risk. Operator: page broker desk and reconcile manually. P0 escalation if NO audit row exists at all (cron didn't fire) — separate 'EOD flatten missed' alert TBD."
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
