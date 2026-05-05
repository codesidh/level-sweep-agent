import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';
import { RouterLink } from '@angular/router';
import { JournalApi } from '../../core/api/journal.api';
import { CalendarApi } from '../../core/api/calendar.api';
import { AuditRow } from '../../core/models/journal.model';
import { MarketDay } from '../../core/models/calendar.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { formatDateEt, formatInstantEt, formatPrice, formatRMultiple } from '../../shared/format';
import { rthStatus, RthStatus } from '../../shared/rth';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

const idle = <T>(): AsyncState<T> => ({ status: 'idle', data: null });

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Hero -->
    <section class="mb-12 text-center">
      <span class="pill mb-6 inline-flex">
        <span class="h-1.5 w-1.5 rounded-full bg-accent-emerald" aria-hidden="true"></span>
        Live · LevelSweep · {{ rth().etLabel }}
      </span>
      <h1 class="mx-auto max-w-3xl text-5xl font-bold leading-[1.05] tracking-tight md:text-6xl">
        <span class="gradient-text">Operator Dashboard</span>
      </h1>
      <p class="mx-auto mt-5 max-w-2xl text-base text-fg-secondary md:text-lg">
        Read-only view into the four-level liquidity sweep strategy. Positions, fills, narratives,
        reviews, projections, and the conversational assistant — all served from the BFF.
      </p>
    </section>

    <!-- Top stat tiles -->
    <section class="mb-10 grid grid-cols-2 gap-4 md:grid-cols-4">
      <div class="tile">
        <div class="mb-3 grid h-9 w-9 place-items-center rounded-tile bg-accent-blue/15">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-accent-blue">
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
        </div>
        <div class="text-3xl font-bold num">{{ rth().etLabel.slice(0, 5) }}</div>
        <div class="mt-1 text-xs uppercase tracking-widest text-fg-muted">{{ rthLabel() }}</div>
      </div>

      <div class="tile">
        <div class="mb-3 grid h-9 w-9 place-items-center rounded-tile bg-accent-emerald/15">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-accent-emerald">
            <path d="M3 17l4 0l3-7l4 4l4-8l3 0" />
          </svg>
        </div>
        <div class="text-3xl font-bold num">{{ fillCount() }}</div>
        <div class="mt-1 text-xs uppercase tracking-widest text-fg-muted">Fills today</div>
      </div>

      <div class="tile">
        <div class="mb-3 grid h-9 w-9 place-items-center rounded-tile bg-accent-pink/15">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-accent-pink">
            <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
          </svg>
        </div>
        <div class="text-3xl font-bold num"
             [class.text-accent-emerald]="totalR() > 0"
             [class.text-accent-red]="totalR() < 0">
          {{ formatRMultiple(totalR()) }}
        </div>
        <div class="mt-1 text-xs uppercase tracking-widest text-fg-muted">R-multiple · today</div>
      </div>

      <div class="tile">
        <div class="mb-3 grid h-9 w-9 place-items-center rounded-tile bg-accent-amber/15">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-accent-amber">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
        </div>
        <div class="text-3xl font-bold num">{{ formatDateEt(today()?.date ?? null) }}</div>
        <div class="mt-1 text-xs uppercase tracking-widest text-fg-muted">{{ tradingDayLabel() }}</div>
      </div>
    </section>

    <!-- Three-up panels -->
    <div class="grid grid-cols-1 gap-6 md:grid-cols-3">
      <!-- (a) Today's RTH status -->
      <section class="glass-card">
        <div class="card-header">RTH Session</div>
        @switch (calendarState().status) {
          @case ('loading') {
            <app-loading-skeleton [rows]="3" />
          }
          @case ('error') {
            <app-error-banner
              title="Calendar unavailable"
              [message]="calendarState().error ?? ''"
              (retryClick)="loadCalendar()"
            />
          }
          @case ('ok') {
            <div class="space-y-4">
              <div>
                <div class="text-4xl font-bold num text-fg-primary">{{ rth().etLabel }}</div>
                <div class="mt-2">
                  @if (rth().state === 'RTH') {
                    <span class="pill pill-ok">
                      <span class="h-1.5 w-1.5 rounded-full bg-accent-emerald" aria-hidden="true"></span>
                      Open
                    </span>
                  } @else if (rth().state === 'PRE') {
                    <span class="pill pill-warn">Pre-market</span>
                  } @else if (rth().state === 'POST') {
                    <span class="pill pill-bad">Closed · Post</span>
                  } @else {
                    <span class="pill pill-bad">Weekend</span>
                  }
                </div>
              </div>
              <div class="text-sm text-fg-secondary">{{ rth().nextLabel }}</div>
              @if (rth().minutesToNext >= 0) {
                <div class="text-sm">
                  <span class="text-fg-muted">in</span>
                  <span class="num text-fg-primary">{{ countdown() }}</span>
                </div>
              }
              <div class="border-t border-subtle pt-4 space-y-1.5 text-sm text-fg-secondary">
                <div class="flex items-center justify-between">
                  <span class="text-fg-muted">Date</span>
                  <span class="num">{{ formatDateEt(today()?.date ?? null) }}</span>
                </div>
                <div class="flex items-center justify-between">
                  <span class="text-fg-muted">Trading day</span>
                  @if (today()?.isTradingDay) {
                    <span class="pill pill-ok">Yes</span>
                  } @else {
                    <span class="pill pill-bad">No</span>
                  }
                </div>
                @if (today()?.isHoliday) {
                  <div class="flex items-center justify-between">
                    <span class="text-fg-muted">Holiday</span>
                    <span>{{ today()?.holidayName }}</span>
                  </div>
                }
                @if (today()?.isHalfDay) {
                  <div class="flex items-center justify-between">
                    <span class="text-fg-muted">Half-day</span>
                    <span>13:00 ET close</span>
                  </div>
                }
                @if (today()?.isFomcDay) {
                  <span class="pill pill-warn">FOMC day</span>
                }
              </div>
            </div>
          }
        }
      </section>

      <!-- (b) Today's totals -->
      <section class="glass-card">
        <div class="card-header">Today · Totals</div>
        @switch (journalState().status) {
          @case ('loading') {
            <app-loading-skeleton [rows]="3" />
          }
          @case ('error') {
            <app-error-banner
              title="Journal unavailable"
              [message]="journalState().error ?? ''"
              (retryClick)="loadJournal()"
            />
          }
          @case ('ok') {
            <div class="space-y-5">
              <div>
                <div class="text-xs uppercase tracking-widest text-fg-muted">Fills</div>
                <div class="text-5xl font-bold num">{{ fillCount() }}</div>
              </div>
              <div>
                <div class="text-xs uppercase tracking-widest text-fg-muted">Total R</div>
                <div
                  class="text-5xl font-bold num"
                  [class.text-accent-emerald]="totalR() > 0"
                  [class.text-accent-red]="totalR() < 0"
                >
                  {{ formatRMultiple(totalR()) }}
                </div>
              </div>
              <div class="text-xs text-fg-muted">
                Source · journal-service · audit_log.events · UTC window
              </div>
            </div>
          }
        }
      </section>

      <!-- (c) Latest 5 fills -->
      <section class="glass-card">
        <div class="card-header flex items-center justify-between">
          <span>Latest fills</span>
          <a class="text-[11px] font-medium text-accent-blue hover:underline" routerLink="/journal">
            View all →
          </a>
        </div>
        @switch (journalState().status) {
          @case ('loading') {
            <app-loading-skeleton [rows]="5" />
          }
          @case ('error') {
            <app-error-banner
              title="Journal unavailable"
              [message]="journalState().error ?? ''"
              (retryClick)="loadJournal()"
            />
          }
          @case ('ok') {
            @if (latestFills().length === 0) {
              <div class="text-sm text-fg-muted">No fills today.</div>
            } @else {
              <ul class="divide-y divide-subtle">
                @for (row of latestFills(); track row._id ?? row.occurred_at) {
                  <li class="py-3 first:pt-0 last:pb-0">
                    <div class="flex items-center justify-between gap-2">
                      <span class="num text-sm font-medium">{{ contractOf(row) }}</span>
                      <span
                        class="pill"
                        [class.pill-ok]="sideOf(row) === 'BUY'"
                        [class.pill-bad]="sideOf(row) === 'SELL'"
                      >
                        {{ sideOf(row) || '—' }}
                      </span>
                    </div>
                    <div class="mt-1 flex items-center justify-between text-xs">
                      <span class="num text-fg-secondary">{{ formatPrice(priceOf(row)) }}</span>
                      <span class="text-fg-muted">{{ formatInstantEt(row.occurred_at ?? null) }}</span>
                    </div>
                  </li>
                }
              </ul>
            }
          }
        }
      </section>
    </div>
  `,
})
export class DashboardPage implements OnInit {
  private readonly journalApi = inject(JournalApi);
  private readonly calendarApi = inject(CalendarApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly journalState = signal<AsyncState<ReadonlyArray<AuditRow>>>(idle());
  readonly calendarState = signal<AsyncState<MarketDay>>(idle());
  readonly tick = signal<number>(Date.now());

  readonly today = computed(() => this.calendarState().data);
  readonly rth = computed<RthStatus>(() => {
    void this.tick();
    return rthStatus();
  });
  readonly countdown = computed(() => {
    const m = this.rth().minutesToNext;
    if (m < 0) return '';
    const hours = Math.floor(m / 60);
    const mins = m % 60;
    return `${hours.toString().padStart(2, '0')}:${mins.toString().padStart(2, '0')}`;
  });

  readonly rthLabel = computed(() => {
    switch (this.rth().state) {
      case 'RTH':
        return 'RTH OPEN';
      case 'PRE':
        return 'PRE-MARKET';
      case 'POST':
        return 'AFTER HOURS';
      default:
        return 'WEEKEND';
    }
  });
  readonly tradingDayLabel = computed(() => {
    const d = this.today();
    if (!d) return 'Today';
    if (d.isHoliday) return 'Holiday';
    if (d.isHalfDay) return 'Half-day';
    if (d.isFomcDay) return 'FOMC day';
    return d.isTradingDay ? 'Trading day' : 'Closed';
  });

  readonly fills = computed<ReadonlyArray<AuditRow>>(() => {
    const rows = this.journalState().data ?? [];
    return rows.filter((r) => r.event_type === 'FILL' || r.event_type === 'TRADE_FILLED');
  });
  readonly fillCount = computed(() => this.fills().length);
  readonly latestFills = computed(() =>
    [...this.fills()]
      .sort((a, b) => (b.occurred_at ?? '').localeCompare(a.occurred_at ?? ''))
      .slice(0, 5),
  );
  readonly totalR = computed(() => this.fills().reduce((acc, row) => acc + this.rOf(row), 0));

  protected readonly formatPrice = formatPrice;
  protected readonly formatInstantEt = formatInstantEt;
  protected readonly formatDateEt = formatDateEt;
  protected readonly formatRMultiple = formatRMultiple;

  ngOnInit(): void {
    this.loadCalendar();
    this.loadJournal();
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.tick.set(Date.now()));
  }

  loadCalendar(): void {
    this.calendarState.set({ status: 'loading', data: null });
    this.calendarApi
      .today()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.calendarState.set({ status: 'ok', data }),
        error: (err: unknown) =>
          this.calendarState.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  loadJournal(): void {
    this.journalState.set({ status: 'loading', data: null });
    const today = todayUtcWindow();
    this.journalApi
      .list({ from: today.from, to: today.to, size: 200 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.journalState.set({ status: 'ok', data: page.rows }),
        error: (err: unknown) =>
          this.journalState.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  contractOf(row: AuditRow): string {
    const v = row.payload['contractSymbol'] ?? row.payload['symbol'] ?? row.payload['occSymbol'];
    return typeof v === 'string' ? v : '—';
  }

  sideOf(row: AuditRow): string {
    const v = row.payload['side'] ?? row.payload['direction'];
    return typeof v === 'string' ? v.toUpperCase() : '';
  }

  priceOf(row: AuditRow): unknown {
    return row.payload['fillPrice'] ?? row.payload['price'] ?? row.payload['avgPrice'];
  }

  private rOf(row: AuditRow): number {
    const v = row.payload['rMultiple'] ?? row.payload['r'];
    if (typeof v === 'number') return v;
    if (typeof v === 'string') {
      const n = Number(v);
      return Number.isFinite(n) ? n : 0;
    }
    return 0;
  }
}

function todayUtcWindow(): { from: string; to: string } {
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 0, 0, 0));
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return { from: start.toISOString(), to: end.toISOString() };
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'status' in err && 'message' in err) {
    const e = err as { status: number; message: string };
    return `HTTP ${e.status}: ${e.message}`;
  }
  return err instanceof Error ? err.message : String(err);
}
