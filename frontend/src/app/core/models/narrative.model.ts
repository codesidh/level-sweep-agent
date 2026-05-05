// Mirrors com.levelsweep.aiagent.narrator.TradeNarrative and
// com.levelsweep.aiagent.reviewer.DailyReport.
//
// TODO(phase 7): the BFF does not yet expose narratives or daily reports as
// dedicated routes — they're persisted in journal.trade_narratives and
// journal.daily_reports MongoDB collections respectively. The Narratives + Reviews
// pages currently surface them via the /api/journal/{tenantId} feed (filtering
// rows by event_type). When dedicated read endpoints land, swap NarrativesApi
// and ReviewsApi to call /api/v1/narratives + /api/v1/reports/daily and remove
// the eventType filter.

export interface TradeNarrative {
  readonly tenantId: string;
  readonly tradeId: string;
  readonly narrative: string;
  readonly generatedAt: string;
  readonly modelUsed: string;
  readonly promptHash: string;
}

export type DailyReportOutcome = 'COMPLETED' | 'SKIPPED_COST_CAP' | 'FAILED';

export interface ConfigProposal {
  readonly key: string;
  readonly currentValue: unknown;
  readonly proposedValue: unknown;
  readonly rationale: string;
}

export interface DailyReport {
  readonly tenantId: string;
  readonly sessionDate: string;
  readonly summary: string;
  readonly anomalies: ReadonlyArray<string>;
  readonly proposals: ReadonlyArray<ConfigProposal>;
  readonly outcome: DailyReportOutcome;
  readonly generatedAt: string;
  readonly modelUsed: string;
  readonly promptHash: string;
  readonly totalTokensUsed: number;
  readonly costUsd: string | number;
}
