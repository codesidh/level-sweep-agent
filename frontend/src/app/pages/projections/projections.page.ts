import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ProjectionApi } from '../../core/api/projection.api';
import { ConfigService } from '../../core/services/config.service';
import { ProjectionRequest, ProjectionResult } from '../../core/models/projection.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { EquityChartComponent } from './equity-chart.component';
import { formatPct, formatUsd, formatInt } from '../../shared/format';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

interface ProjectionForm {
  startingEquity: number;
  winRatePct: number;
  // The task brief uses avg_win_pct + avg_loss_pct; the backend
  // ProjectionRequest collapses to a single lossPct (per-position loss size)
  // and a positionSizePct. We surface both: avgWinPct is informational, the
  // backend ignores it. Phase 7 may extend the engine to honour an asymmetric
  // win amount.
  avgWinPct: number;
  lossPct: number;
  positionSizePct: number;
  sessionsPerWeek: number;
  trades: number;
  iterations: number;
}

@Component({
  selector: 'app-projections',
  standalone: true,
  imports: [FormsModule, LoadingSkeletonComponent, ErrorBannerComponent, EquityChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8">
      <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Projections</span></h1>
      <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
        Monte Carlo run via projection-service. Identical inputs produce identical results —
        deterministic seed from SHA-256(tenantId, request).
      </p>
    </header>

    <form class="glass-card grid grid-cols-2 gap-4 md:grid-cols-4" (submit)="$event.preventDefault(); run()">
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Starting equity
        <input class="input mt-1 num" type="number" min="0.01" step="100" [(ngModel)]="form.startingEquity" name="startingEquity" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Win rate (%)
        <input class="input mt-1 num" type="number" min="0" max="100" step="0.1" [(ngModel)]="form.winRatePct" name="winRatePct" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Avg win (%)
        <input class="input mt-1 num" type="number" min="0" max="100" step="0.1" [(ngModel)]="form.avgWinPct" name="avgWinPct" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Avg loss (%)
        <input class="input mt-1 num" type="number" min="0" max="100" step="0.1" [(ngModel)]="form.lossPct" name="lossPct" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Position size (% equity)
        <input class="input mt-1 num" type="number" min="0.01" max="100" step="0.05" [(ngModel)]="form.positionSizePct" name="positionSizePct" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Sessions per week
        <input class="input mt-1 num" type="number" min="1" max="7" step="1" [(ngModel)]="form.sessionsPerWeek" name="sessionsPerWeek" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Trades (≈ horizon)
        <input class="input mt-1 num" type="number" min="1" step="50" [(ngModel)]="form.trades" name="trades" />
      </label>
      <label class="text-[11px] uppercase tracking-widest text-fg-muted">
        Iterations
        <input class="input mt-1 num" type="number" min="100" max="100000" step="500" [(ngModel)]="form.iterations" name="iterations" />
      </label>
      <div class="col-span-2 flex flex-wrap items-end gap-3 md:col-span-4">
        <button class="btn btn-primary" type="submit" [disabled]="state().status === 'loading'">
          Run projection
        </button>
        <button class="btn" type="button" (click)="loadLast()" [disabled]="state().status === 'loading'">
          Load last cached
        </button>
      </div>
    </form>

    <div class="mt-6">
      @switch (state().status) {
        @case ('loading') {
          <app-loading-skeleton [rows]="6" />
        }
        @case ('error') {
          <app-error-banner title="Projection failed" [message]="state().error ?? ''" (retryClick)="run()" />
        }
        @case ('ok') {
          @if (state().data; as r) {
            <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
              <div class="tile">
                <div class="card-header">Median · P50</div>
                <div class="text-3xl font-bold num">{{ formatUsd(r.p50) }}</div>
              </div>
              <div class="tile">
                <div class="card-header">Mean</div>
                <div class="text-3xl font-bold num">{{ formatUsd(r.mean) }}</div>
              </div>
              <div class="tile">
                <div class="card-header">Ruin probability</div>
                <div class="text-3xl font-bold num"
                     [class.text-accent-red]="r.ruinProbability > 0.05"
                     [class.text-accent-amber]="r.ruinProbability > 0.01 && r.ruinProbability <= 0.05"
                     [class.text-accent-emerald]="r.ruinProbability <= 0.01">
                  {{ formatPct(r.ruinProbability * 100, 2) }}
                </div>
              </div>
            </div>
            <div class="mt-5 grid grid-cols-2 gap-3 md:grid-cols-5 text-sm">
              <div class="tile"><div class="card-header">P10</div><div class="num">{{ formatUsd(r.p10) }}</div></div>
              <div class="tile"><div class="card-header">P25</div><div class="num">{{ formatUsd(r.p25) }}</div></div>
              <div class="tile"><div class="card-header">P50</div><div class="num">{{ formatUsd(r.p50) }}</div></div>
              <div class="tile"><div class="card-header">P75</div><div class="num">{{ formatUsd(r.p75) }}</div></div>
              <div class="tile"><div class="card-header">P90</div><div class="num">{{ formatUsd(r.p90) }}</div></div>
            </div>
            <div class="mt-6">
              <app-equity-chart [result]="r" [startingEquity]="form.startingEquity" />
            </div>
            <div class="mt-3 text-xs text-fg-muted num">
              simulationsRun · {{ formatInt(r.simulationsRun) }} · requestHash {{ r.requestHash.slice(0, 12) }}…
            </div>
          }
        }
      }
    </div>
  `,
})
export class ProjectionsPage {
  private readonly api = inject(ProjectionApi);
  private readonly cfg = inject(ConfigService);
  private readonly destroyRef = inject(DestroyRef);

  protected form: ProjectionForm = {
    startingEquity: 25_000,
    winRatePct: 55,
    avgWinPct: 40,
    lossPct: 50,
    positionSizePct: 5,
    sessionsPerWeek: 5,
    trades: 500,
    iterations: 10_000,
  };

  readonly state = signal<AsyncState<ProjectionResult>>({ status: 'idle', data: null });

  protected readonly formatUsd = formatUsd;
  protected readonly formatPct = formatPct;
  protected readonly formatInt = formatInt;

  run(): void {
    this.state.set({ status: 'loading', data: null });
    const req: ProjectionRequest = {
      tenantId: this.cfg.tenantId(),
      startingEquity: this.form.startingEquity,
      winRatePct: this.form.winRatePct,
      lossPct: this.form.lossPct,
      sessionsPerWeek: this.form.sessionsPerWeek,
      // Backend uses horizonWeeks; trades/sessionsPerWeek collapses to weeks.
      horizonWeeks: Math.max(1, Math.round(this.form.trades / Math.max(1, this.form.sessionsPerWeek))),
      positionSizePct: this.form.positionSizePct,
      simulations: this.form.iterations,
    };
    this.api
      .run(req)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.state.set({ status: 'ok', data }),
        error: (err: unknown) => this.state.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  loadLast(): void {
    this.state.set({ status: 'loading', data: null });
    this.api
      .last()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (doc) => {
          if (doc === null) {
            this.state.set({ status: 'error', data: null, error: 'No cached projection for OWNER yet.' });
            return;
          }
          // Restore the form so the user sees what produced this result.
          this.form = {
            startingEquity: doc.request.startingEquity,
            winRatePct: doc.request.winRatePct,
            avgWinPct: this.form.avgWinPct,
            lossPct: doc.request.lossPct,
            positionSizePct: doc.request.positionSizePct,
            sessionsPerWeek: doc.request.sessionsPerWeek,
            trades: doc.request.sessionsPerWeek * doc.request.horizonWeeks,
            iterations: doc.request.simulations,
          };
          this.state.set({ status: 'ok', data: doc.result });
        },
        error: (err: unknown) => this.state.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }
}

function errMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'status' in err) {
    const e = err as { status: number; message?: string };
    if (e.status === 404) return 'No cached projection yet — click "Run projection" to compute one.';
    return `HTTP ${e.status}${e.message ? ': ' + e.message : ''}`;
  }
  return err instanceof Error ? err.message : String(err);
}
