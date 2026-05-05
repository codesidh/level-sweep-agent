// Mirrors com.levelsweep.journal.audit.AuditRecord (Mongo-persisted) and the
// JournalQueryController response shape:
//   { tenantId, page, size, total, rows: [Document] }
// Each row is a polymorphic Mongo Document; the BFF forwards the JSON
// untouched. We type the well-known top-level fields and leave the payload
// loose because event types vary (FILL, ORDER_SUBMITTED, STOP_TRIGGERED, ...).

export interface AuditRow {
  readonly _id?: string;
  readonly tenant_id: string;
  readonly event_type: string;
  readonly source_service: string;
  readonly occurred_at?: string;
  readonly written_at?: string;
  readonly trace_id?: string | null;
  readonly correlation_id?: string | null;
  readonly payload: Record<string, unknown>;
}

export interface JournalPage {
  readonly tenantId: string;
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly rows: ReadonlyArray<AuditRow>;
}
