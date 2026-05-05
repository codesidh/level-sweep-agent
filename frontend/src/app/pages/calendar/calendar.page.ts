import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { CalendarApi } from '../../core/api/calendar.api';
import { BlackoutResponse, MarketDay, MarketEvent } from '../../core/models/calendar.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

interface CalendarBundle {
  readonly today: MarketDay;
  readonly blackouts: BlackoutResponse;
}

@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Calendar</span></h1>
        <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
          NYSE schedule + FOMC events. Tenant-agnostic — Phase A.
        </p>
      </div>
      <button class="btn" type="button" (click)="reload()">Reload</button>
    </header>

    @switch (state().status) {
      @case ('loading') {
        <app-loading-skeleton [rows]="8" />
      }
      @case ('error') {
        <app-error-banner title="Calendar unavailable" [message]="state().error ?? ''" (retryClick)="reload()" />
      }
      @case ('ok') {
        @if (state().data; as data) {
          <div class="grid grid-cols-1 gap-5 md:grid-cols-3">
            <section class="glass-card md:col-span-2">
              <div class="card-header">Today</div>
              <div class="text-4xl font-bold num">{{ data.today.date }}</div>
              <div class="mt-3 flex flex-wrap gap-2">
                @if (data.today.isTradingDay) {
                  <span class="pill pill-ok">Trading day</span>
                } @else {
                  <span class="pill pill-bad">Closed</span>
                }
                @if (data.today.isHoliday) {
                  <span class="pill pill-warn">Holiday · {{ data.today.holidayName }}</span>
                }
                @if (data.today.isHalfDay) {
                  <span class="pill pill-warn">Half-day · 13:00 ET</span>
                }
                @if (data.today.isFomcDay) {
                  <span class="pill pill-warn">FOMC</span>
                }
              </div>
              @if (data.today.eventNames.length > 0) {
                <ul class="mt-4 list-disc space-y-1 pl-5 text-sm text-fg-primary">
                  @for (e of data.today.eventNames; track e) {
                    <li>{{ e }}</li>
                  }
                </ul>
              }
            </section>

            <section class="glass-card">
              <div class="card-header">Next FOMC</div>
              <div class="num text-2xl font-bold">{{ nextFomc()?.date ?? '—' }}</div>
              <div class="mt-1 text-sm text-fg-secondary">{{ nextFomc()?.name ?? '' }}</div>
            </section>
          </div>

          <section class="glass-card mt-5">
            <div class="card-header">Holidays + closures · next 12 months</div>
            @if (holidays().length === 0) {
              <div class="text-sm text-fg-muted">No upcoming closures in window.</div>
            } @else {
              <ul class="grid grid-cols-1 divide-y divide-subtle sm:grid-cols-2 sm:gap-x-8 sm:divide-y-0">
                @for (e of holidays(); track e.date) {
                  <li class="flex items-baseline justify-between gap-3 border-b border-subtle py-2.5 text-sm last:border-b-0">
                    <span class="num">{{ e.date }}</span>
                    <span class="flex-1 text-fg-secondary">{{ e.name }}</span>
                    <span class="pill" [class.pill-bad]="e.type === 'HOLIDAY'" [class.pill-warn]="e.type !== 'HOLIDAY'">
                      {{ e.type }}
                    </span>
                  </li>
                }
              </ul>
            }
          </section>

          <section class="glass-card mt-5">
            <div class="card-header">All FOMC events · next 12 months</div>
            @if (fomc().length === 0) {
              <div class="text-sm text-fg-muted">No FOMC events in window.</div>
            } @else {
              <ul class="grid grid-cols-1 divide-y divide-subtle sm:grid-cols-2 sm:gap-x-8 sm:divide-y-0">
                @for (e of fomc(); track e.date) {
                  <li class="flex items-baseline justify-between gap-3 border-b border-subtle py-2.5 text-sm last:border-b-0">
                    <span class="num">{{ e.date }}</span>
                    <span class="flex-1 text-fg-secondary">{{ e.name }}</span>
                    <span class="pill pill-warn">{{ e.type }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        }
      }
    }
  `,
})
export class CalendarPage implements OnInit {
  private readonly api = inject(CalendarApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<AsyncState<CalendarBundle>>({ status: 'idle', data: null });

  readonly events = computed<ReadonlyArray<MarketEvent>>(() => this.state().data?.blackouts.events ?? []);
  readonly holidays = computed(() => this.events().filter((e) => e.type === 'HOLIDAY' || e.type === 'EARLY_CLOSE'));
  readonly fomc = computed(() => this.events().filter((e) => e.type === 'FOMC_MEETING' || e.type === 'FOMC_MINUTES'));
  readonly nextFomc = computed<MarketEvent | undefined>(() => {
    const today = new Date().toISOString().slice(0, 10);
    return this.fomc().find((e) => e.date >= today);
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.state.set({ status: 'loading', data: null });
    const today = new Date();
    const from = today.toISOString().slice(0, 10);
    const toDate = new Date(today);
    toDate.setUTCFullYear(toDate.getUTCFullYear() + 1);
    const to = toDate.toISOString().slice(0, 10);

    forkJoin({
      today: this.api.today(),
      blackouts: this.api.blackoutDates(from, to),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.state.set({ status: 'ok', data }),
        error: (err: unknown) => this.state.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'status' in err) {
    const e = err as { status: number; message?: string };
    if (e.status === 404) {
      return 'Calendar route /api/calendar/blackout-dates not yet exposed by BFF — TODO(phase 7).';
    }
    return `HTTP ${e.status}${e.message ? ': ' + e.message : ''}`;
  }
  return err instanceof Error ? err.message : String(err);
}
