import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { JournalApi } from '../../core/api/journal.api';
import { AuditRow } from '../../core/models/journal.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { formatInstantEt, formatInstantUtc, formatPrice, formatRMultiple } from '../../shared/format';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

@Component({
  selector: 'app-journal',
  standalone: true,
  imports: [FormsModule, LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Trade Journal</span></h1>
        <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
          Audit log from journal-service · {{ "audit_log.events" }} · per-tenant scoped.
        </p>
      </div>
      <div class="flex flex-wrap items-end gap-3">
        <label class="text-[11px] uppercase tracking-widest text-fg-muted">
          Date (UTC)
          <input class="input mt-1" type="date" [(ngModel)]="dateInput" (change)="onDateChange()" />
        </label>
        <label class="text-[11px] uppercase tracking-widest text-fg-muted">
          Type
          <select class="input mt-1" [(ngModel)]="typeFilter" (change)="reload()">
            <option value="">all</option>
            <option value="FILL">FILL</option>
            <option value="TRADE_FILLED">TRADE_FILLED</option>
            <option value="ORDER_SUBMITTED">ORDER_SUBMITTED</option>
            <option value="ORDER_REJECTED">ORDER_REJECTED</option>
            <option value="STOP_TRIGGERED">STOP_TRIGGERED</option>
            <option value="TRAIL_BREACHED">TRAIL_BREACHED</option>
            <option value="EOD_FLATTENED">EOD_FLATTENED</option>
          </select>
        </label>
        <button class="btn" type="button" (click)="reload()">Reload</button>
      </div>
    </header>

    @switch (state().status) {
      @case ('loading') {
        <app-loading-skeleton [rows]="10" />
      }
      @case ('error') {
        <app-error-banner title="Journal unavailable" [message]="state().error ?? ''" (retryClick)="reload()" />
      }
      @case ('ok') {
        <div class="glass-card overflow-x-auto !p-0">
          <table class="min-w-full divide-y divide-subtle text-sm">
            <thead class="text-[10px] uppercase tracking-widest text-fg-muted">
              <tr>
                <th class="px-4 py-3 text-left">When (ET)</th>
                <th class="px-4 py-3 text-left">Type</th>
                <th class="px-4 py-3 text-left">Trade ID</th>
                <th class="px-4 py-3 text-left">Symbol</th>
                <th class="px-4 py-3 text-right">Price</th>
                <th class="px-4 py-3 text-right">R</th>
                <th class="px-4 py-3 text-left">Source</th>
                <th class="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody class="divide-y divide-subtle">
              @if (rows().length === 0) {
                <tr>
                  <td colspan="8" class="px-4 py-8 text-center text-fg-muted">No entries.</td>
                </tr>
              }
              @for (row of rows(); track $index) {
                <tr class="cursor-pointer transition hover:bg-bg-glass-hover" (click)="toggle($index)">
                  <td class="px-4 py-3 num text-xs text-fg-secondary">{{ formatInstantEt(row.occurred_at ?? null) }}</td>
                  <td class="px-4 py-3"><span class="pill">{{ row.event_type }}</span></td>
                  <td class="px-4 py-3 num text-xs text-fg-secondary">{{ tradeIdOf(row) }}</td>
                  <td class="px-4 py-3 num">{{ symbolOf(row) }}</td>
                  <td class="px-4 py-3 text-right num">{{ formatPrice(priceOf(row)) }}</td>
                  <td class="px-4 py-3 text-right num"
                      [class.text-accent-emerald]="rOf(row) > 0"
                      [class.text-accent-red]="rOf(row) < 0">
                    {{ formatRMultiple(rOf(row)) }}
                  </td>
                  <td class="px-4 py-3 text-xs text-fg-muted">{{ row.source_service }}</td>
                  <td class="px-4 py-3 text-right text-xs text-fg-muted">
                    {{ expanded() === $index ? '▾' : '▸' }}
                  </td>
                </tr>
                @if (expanded() === $index) {
                  <tr class="bg-bg-glass">
                    <td colspan="8" class="px-4 py-4">
                      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 text-xs">
                        <div>
                          <div class="card-header">Metadata</div>
                          <dl class="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5">
                            <dt class="text-fg-muted">tenant_id</dt>
                            <dd class="num">{{ row.tenant_id }}</dd>
                            <dt class="text-fg-muted">written_at</dt>
                            <dd class="num">{{ formatInstantUtc(row.written_at ?? null) }}</dd>
                            <dt class="text-fg-muted">trace_id</dt>
                            <dd class="num">{{ row.trace_id ?? '—' }}</dd>
                            <dt class="text-fg-muted">correlation_id</dt>
                            <dd class="num">{{ row.correlation_id ?? '—' }}</dd>
                          </dl>
                        </div>
                        <div>
                          <div class="card-header">Payload</div>
                          <pre class="overflow-x-auto rounded-tile border border-subtle bg-bg-base/60 p-3 text-[11px] num text-fg-secondary">{{ stringify(row.payload) }}</pre>
                        </div>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
})
export class JournalPage implements OnInit {
  private readonly api = inject(JournalApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<AsyncState<ReadonlyArray<AuditRow>>>({ status: 'idle', data: null });
  readonly expanded = signal<number | null>(null);

  protected dateInput = todayUtcDate();
  protected typeFilter = '';

  readonly rows = computed<ReadonlyArray<AuditRow>>(() => this.state().data ?? []);

  protected readonly formatPrice = formatPrice;
  protected readonly formatRMultiple = formatRMultiple;
  protected readonly formatInstantEt = formatInstantEt;
  protected readonly formatInstantUtc = formatInstantUtc;

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.state.set({ status: 'loading', data: null });
    const window = utcDayWindow(this.dateInput);
    const opts: { from: string; to: string; size: number; type?: string } = {
      from: window.from,
      to: window.to,
      size: 200,
    };
    if (this.typeFilter) {
      opts.type = this.typeFilter;
    }
    this.api
      .list(opts)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.state.set({ status: 'ok', data: page.rows }),
        error: (err: unknown) => this.state.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  onDateChange(): void {
    this.reload();
  }

  toggle(idx: number): void {
    this.expanded.set(this.expanded() === idx ? null : idx);
  }

  tradeIdOf(row: AuditRow): string {
    const v = row.payload['tradeId'] ?? row.payload['orderId'] ?? row.payload['id'];
    return typeof v === 'string' ? v.slice(0, 12) : '—';
  }

  symbolOf(row: AuditRow): string {
    const v = row.payload['contractSymbol'] ?? row.payload['symbol'] ?? row.payload['occSymbol'];
    return typeof v === 'string' ? v : '—';
  }

  priceOf(row: AuditRow): unknown {
    return row.payload['fillPrice'] ?? row.payload['price'] ?? row.payload['avgPrice'] ?? row.payload['limitPrice'];
  }

  rOf(row: AuditRow): number {
    const v = row.payload['rMultiple'] ?? row.payload['r'];
    if (typeof v === 'number') return v;
    if (typeof v === 'string') {
      const n = Number(v);
      return Number.isFinite(n) ? n : 0;
    }
    return 0;
  }

  stringify(v: unknown): string {
    try {
      return JSON.stringify(v, null, 2);
    } catch {
      return String(v);
    }
  }
}

function todayUtcDate(): string {
  const d = new Date();
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

function utcDayWindow(yyyymmdd: string): { from: string; to: string } {
  // yyyymmdd is "YYYY-MM-DD" — explicit destructuring keeps strict-index-signature
  // happy without `??` fallbacks for valid inputs.
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(yyyymmdd);
  const now = new Date();
  const y = m ? Number(m[1]) : now.getUTCFullYear();
  const mo = m ? Number(m[2]) - 1 : now.getUTCMonth();
  const d = m ? Number(m[3]) : now.getUTCDate();
  const start = new Date(Date.UTC(y, mo, d, 0, 0, 0));
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return { from: start.toISOString(), to: end.toISOString() };
}

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'status' in err) {
    const e = err as { status: number; message?: string };
    return `HTTP ${e.status}${e.message ? ': ' + e.message : ''}`;
  }
  return err instanceof Error ? err.message : String(err);
}
