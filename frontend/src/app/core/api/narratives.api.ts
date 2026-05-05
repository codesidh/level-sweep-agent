import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { TradeNarrative } from '../models/narrative.model';
import { JournalPage } from '../models/journal.model';

@Injectable({ providedIn: 'root' })
export class NarrativesApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // TODO(phase 7): the BFF does not yet expose a dedicated narratives route.
  // Trade narratives live in journal.trade_narratives (Mongo); they're not
  // joined into the audit_log feed today. Until the BFF grows
  // GET /api/v1/narratives?tenantId&limit, the Narratives page falls back to
  // filtering the journal feed by event_type=NARRATIVE_GENERATED — a row the
  // narrator service writes alongside the persisted TradeNarrative document
  // (see TradeEventNarratorListener). The payload contains the same fields as
  // the Java record; this method extracts and shapes them.
  list(limit = 50): Observable<ReadonlyArray<TradeNarrative>> {
    const params = new HttpParams().set('type', 'NARRATIVE_GENERATED').set('size', String(limit));
    return this.http
      .get<JournalPage>(`${this.cfg.apiBase}/journal/${this.cfg.tenantId()}`, { params })
      .pipe(map((page) => page.rows.map((row) => mapRowToNarrative(row.payload, row.tenant_id, row.occurred_at))));
  }
}

function mapRowToNarrative(
  payload: Record<string, unknown>,
  tenantId: string,
  occurredAt: string | undefined,
): TradeNarrative {
  return {
    tenantId: typeof payload['tenantId'] === 'string' ? (payload['tenantId'] as string) : tenantId,
    tradeId: stringOrEmpty(payload['tradeId']),
    narrative: stringOrEmpty(payload['narrative']),
    generatedAt: typeof payload['generatedAt'] === 'string' ? (payload['generatedAt'] as string) : occurredAt ?? '',
    modelUsed: stringOrEmpty(payload['modelUsed']),
    promptHash: stringOrEmpty(payload['promptHash']),
  };
}

function stringOrEmpty(v: unknown): string {
  return typeof v === 'string' ? v : '';
}
