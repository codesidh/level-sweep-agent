import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReviewsApi } from '../../core/api/reviews.api';
import { DailyReport } from '../../core/models/narrative.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { formatInstantUtc, formatUsd, formatInt } from '../../shared/format';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

@Component({
  selector: 'app-reviews',
  standalone: true,
  imports: [NgClass, LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Daily Reviews</span></h1>
        <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
          End-of-day review by the Daily Reviewer (Opus 4.7). Proposals are advisory only —
          Phase A guardrail #2.
        </p>
      </div>
      <button class="btn" type="button" (click)="reload()">Reload</button>
    </header>

    @switch (state().status) {
      @case ('loading') {
        <app-loading-skeleton [rows]="6" />
      }
      @case ('error') {
        <app-error-banner title="Reviews unavailable" [message]="state().error ?? ''" (retryClick)="reload()" />
      }
      @case ('ok') {
        @if (rows().length === 0) {
          <div class="glass-card text-sm text-fg-secondary">
            No daily reviews yet. The Daily Reviewer runs at 16:30 ET each trading day.
          </div>
        } @else {
          <ul class="space-y-5">
            @for (r of rows(); track r.promptHash + r.sessionDate) {
              <li class="glass-card">
                <div class="mb-3 flex flex-wrap items-baseline gap-2">
                  <span class="text-xl font-bold num">{{ r.sessionDate }}</span>
                  <span class="pill" [ngClass]="outcomePillClass(r)">{{ r.outcome }}</span>
                  <span class="pill">{{ r.modelUsed }}</span>
                  <span class="ml-auto text-xs num text-fg-muted">{{ formatInstantUtc(r.generatedAt) }}</span>
                </div>
                <div class="whitespace-pre-line leading-relaxed text-fg-primary">{{ r.summary }}</div>

                @if (r.anomalies.length > 0) {
                  <div class="mt-5">
                    <div class="card-header">Anomalies</div>
                    <ul class="list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-fg-primary">
                      @for (a of r.anomalies; track a) {
                        <li>{{ a }}</li>
                      }
                    </ul>
                  </div>
                }

                @if (r.proposals.length > 0) {
                  <div class="mt-5">
                    <div class="card-header">Advisory proposals · not applied</div>
                    <ul class="space-y-2 text-sm">
                      @for (p of r.proposals; track p.key) {
                        <li class="rounded-tile border border-subtle bg-bg-base/40 p-3">
                          <div class="flex flex-wrap items-baseline gap-2">
                            <span class="num text-fg-muted">{{ p.key }}</span>
                            <span class="num text-accent-red">{{ stringify(p.currentValue) }}</span>
                            <span class="text-fg-muted">→</span>
                            <span class="num text-accent-emerald">{{ stringify(p.proposedValue) }}</span>
                          </div>
                          <div class="mt-1 text-xs text-fg-muted">{{ p.rationale }}</div>
                        </li>
                      }
                    </ul>
                  </div>
                }

                <div class="mt-5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-fg-muted">
                  <span>Tokens · <span class="num text-fg-primary">{{ formatInt(r.totalTokensUsed) }}</span></span>
                  <span>Cost · <span class="num text-fg-primary">{{ formatUsd(r.costUsd, 4) }}</span></span>
                  <span class="num">prompt {{ r.promptHash.slice(0, 12) }}…</span>
                </div>
              </li>
            }
          </ul>
        }
      }
    }
  `,
  // ngClass shim — needed for the dynamic pill class table.
  // (We import CommonModule's NgClass directive for that one usage.)
  // It's the only structural-template shim in the codebase.
  styles: [],
})
export class ReviewsPage implements OnInit {
  private readonly api = inject(ReviewsApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<AsyncState<ReadonlyArray<DailyReport>>>({ status: 'idle', data: null });

  protected readonly formatInstantUtc = formatInstantUtc;
  protected readonly formatUsd = formatUsd;
  protected readonly formatInt = formatInt;

  rows(): ReadonlyArray<DailyReport> {
    return this.state().data ?? [];
  }

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.state.set({ status: 'loading', data: null });
    this.api
      .list(30)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.state.set({ status: 'ok', data }),
        error: (err: unknown) => this.state.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  outcomePillClass(r: DailyReport): Record<string, boolean> {
    return {
      'pill-ok': r.outcome === 'COMPLETED',
      'pill-warn': r.outcome === 'SKIPPED_COST_CAP',
      'pill-bad': r.outcome === 'FAILED',
    };
  }

  stringify(v: unknown): string {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'object') {
      try {
        return JSON.stringify(v);
      } catch {
        return String(v);
      }
    }
    return String(v);
  }
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'status' in err) {
    const e = err as { status: number; message?: string };
    return `HTTP ${e.status}${e.message ? ': ' + e.message : ''}`;
  }
  return err instanceof Error ? err.message : String(err);
}
