import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { NarrativesApi } from '../../core/api/narratives.api';
import { TradeNarrative } from '../../core/models/narrative.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { formatInstantUtc } from '../../shared/format';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

@Component({
  selector: 'app-narratives',
  standalone: true,
  imports: [RouterLink, LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Trade Narratives</span></h1>
        <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
          Post-trade explanations from the Trade Narrator (Sonnet 4.6). One per fill, rejection,
          stop, trail, or EOD-flatten event.
        </p>
      </div>
      <button class="btn" type="button" (click)="reload()">Reload</button>
    </header>

    @switch (state().status) {
      @case ('loading') {
        <app-loading-skeleton [rows]="6" />
      }
      @case ('error') {
        <app-error-banner title="Narratives unavailable" [message]="state().error ?? ''" (retryClick)="reload()" />
      }
      @case ('ok') {
        @if (rows().length === 0) {
          <div class="glass-card text-sm text-fg-secondary">
            No narratives yet. Each Kafka trade event triggers one — wait for the next fill, or check
            the journal page for unprocessed events.
          </div>
        } @else {
          <ul class="space-y-4">
            @for (n of rows(); track n.promptHash + n.tradeId) {
              <li class="glass-card">
                <div class="mb-3 flex flex-wrap items-baseline gap-2">
                  <span class="num text-xs text-fg-muted">{{ n.tradeId }}</span>
                  <span class="pill">{{ n.modelUsed }}</span>
                  <span class="ml-auto text-xs num text-fg-muted">{{ formatInstantUtc(n.generatedAt) }}</span>
                </div>
                <div class="leading-relaxed text-fg-primary">{{ n.narrative }}</div>
                <div class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-fg-muted">
                  <a class="text-accent-blue hover:underline" routerLink="/journal">View journal entry →</a>
                  <span class="opacity-50">·</span>
                  <span class="num">prompt {{ n.promptHash.slice(0, 12) }}…</span>
                </div>
              </li>
            }
          </ul>
        }
      }
    }
  `,
})
export class NarrativesPage implements OnInit {
  private readonly api = inject(NarrativesApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<AsyncState<ReadonlyArray<TradeNarrative>>>({ status: 'idle', data: null });

  protected readonly formatInstantUtc = formatInstantUtc;

  rows(): ReadonlyArray<TradeNarrative> {
    return this.state().data ?? [];
  }

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.state.set({ status: 'loading', data: null });
    this.api
      .list(50)
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
    return `HTTP ${e.status}${e.message ? ': ' + e.message : ''}`;
  }
  return err instanceof Error ? err.message : String(err);
}
