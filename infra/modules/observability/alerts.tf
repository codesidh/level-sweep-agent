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
