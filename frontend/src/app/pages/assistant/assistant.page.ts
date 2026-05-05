import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AssistantApi } from '../../core/api/assistant.api';
import { AssistantTurn, ConversationSummary } from '../../core/models/assistant.model';
import { LoadingSkeletonComponent } from '../../shared/loading-skeleton.component';
import { ErrorBannerComponent } from '../../shared/error-banner.component';
import { formatInstantUtc, formatUsd } from '../../shared/format';

interface AsyncState<T> {
  readonly status: 'idle' | 'loading' | 'ok' | 'error';
  readonly data: T | null;
  readonly error?: string;
}

@Component({
  selector: 'app-assistant',
  standalone: true,
  imports: [FormsModule, LoadingSkeletonComponent, ErrorBannerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-8">
      <h1 class="text-4xl font-bold tracking-tight"><span class="gradient-text">Assistant</span></h1>
      <p class="mt-2 max-w-2xl text-sm text-fg-secondary">
        Conversational read-only Assistant (Sonnet 4.6). It cannot place orders or modify state —
        Phase A guardrail #2.
      </p>
    </header>

    <div class="grid grid-cols-1 gap-5 md:grid-cols-3">
      <!-- LEFT: recent conversations -->
      <aside class="glass-card md:col-span-1">
        <div class="card-header flex items-center justify-between">
          <span>Recent threads</span>
          <button class="btn !px-3 !py-1 text-xs" type="button" (click)="newThread()">+ New</button>
        </div>
        @switch (convosState().status) {
          @case ('loading') {
            <app-loading-skeleton [rows]="5" />
          }
          @case ('error') {
            <app-error-banner
              [canRetry]="true"
              title="Conversations unavailable"
              [message]="convosState().error ?? ''"
              (retryClick)="loadConversations()"
            />
          }
          @case ('ok') {
            @if ((convosState().data?.length ?? 0) === 0) {
              <div class="text-sm text-fg-muted">No conversations yet — say hello below.</div>
            } @else {
              <ul class="space-y-1">
                @for (c of convosState().data ?? []; track c.conversationId) {
                  <li>
                    <button
                      type="button"
                      class="w-full rounded-tile px-3 py-2.5 text-left text-sm transition hover:bg-bg-glass-hover"
                      [class.bg-bg-glass-hover]="c.conversationId === activeConversationId()"
                      (click)="selectConversation(c.conversationId)"
                    >
                      <div class="num text-xs text-fg-secondary">{{ c.conversationId.slice(0, 12) }}…</div>
                      <div class="mt-0.5 text-[11px] text-fg-muted">
                        <span class="num">{{ c.turnCount }}</span> turns ·
                        <span class="num">{{ formatUsd(c.totalCostUsd, 4) }}</span>
                      </div>
                      <div class="mt-0.5 text-[10px] text-fg-muted/70 num">{{ formatInstantUtc(c.updatedAt) }}</div>
                    </button>
                  </li>
                }
              </ul>
            }
          }
        }
      </aside>

      <!-- RIGHT: active thread -->
      <section class="glass-card md:col-span-2 flex flex-col">
        <div class="card-header flex items-center justify-between">
          <span>
            @if (activeConversationId()) {
              Conversation <span class="num">{{ activeConversationId()?.slice(0, 12) }}…</span>
            } @else {
              New conversation
            }
          </span>
          @if (sending()) {
            <span class="pill pill-warn">Sending…</span>
          }
        </div>

        <div class="min-h-[320px] flex-1 space-y-3 overflow-y-auto py-2">
          @if (turns().length === 0 && !sending()) {
            <div class="text-sm text-fg-muted">
              Ask about today's positions, recent narratives, or indicator state. The Assistant
              cannot take actions.
            </div>
          }
          @for (turn of turns(); track $index) {
            <div [class.text-right]="turn.role === 'user'">
              <div
                class="inline-block max-w-prose rounded-2xl px-4 py-2.5 text-sm leading-relaxed"
                [class.bg-gradient-to-br]="turn.role === 'user'"
                [class.from-accent-blue]="turn.role === 'user'"
                [class.to-accent-cyan]="turn.role === 'user'"
                [class.text-white]="turn.role === 'user'"
                [class.shadow-glow-blue]="turn.role === 'user'"
                [class.glass]="turn.role === 'assistant'"
                [class.text-fg-primary]="turn.role === 'assistant'"
              >
                <div class="whitespace-pre-line">{{ turn.content }}</div>
                <div class="mt-1.5 text-[10px] opacity-70 num">
                  {{ formatInstantUtc(turn.ts) }} · cost {{ formatUsd(turn.costUsd, 4) }}
                </div>
              </div>
            </div>
          }
          @if (sendError()) {
            <app-error-banner
              [canRetry]="false"
              title="Chat failed"
              [message]="sendError() ?? ''"
            />
          }
        </div>

        <form class="mt-3 flex gap-2" (submit)="$event.preventDefault(); send()">
          <input
            class="input flex-1"
            type="text"
            placeholder="Ask about today's session…"
            [(ngModel)]="draft"
            name="draft"
            [disabled]="sending()"
            autocomplete="off"
          />
          <button class="btn btn-primary" type="submit" [disabled]="sending() || !draft.trim()">
            Send
          </button>
        </form>
      </section>
    </div>
  `,
})
export class AssistantPage implements OnInit {
  private readonly api = inject(AssistantApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly convosState = signal<AsyncState<ReadonlyArray<ConversationSummary>>>({ status: 'idle', data: null });

  readonly activeConversationId = signal<string | null>(null);
  readonly turns = signal<ReadonlyArray<AssistantTurn>>([]);
  readonly sending = signal<boolean>(false);
  readonly sendError = signal<string | null>(null);

  protected draft = '';

  protected readonly formatInstantUtc = formatInstantUtc;
  protected readonly formatUsd = formatUsd;

  ngOnInit(): void {
    this.loadConversations();
  }

  loadConversations(): void {
    this.convosState.set({ status: 'loading', data: null });
    this.api
      .conversations(20)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.convosState.set({ status: 'ok', data }),
        error: (err: unknown) => this.convosState.set({ status: 'error', data: null, error: errMsg(err) }),
      });
  }

  selectConversation(id: string): void {
    this.activeConversationId.set(id);
    this.api
      .conversation(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => this.turns.set(view.turns),
        error: (err: unknown) => this.sendError.set(errMsg(err)),
      });
  }

  newThread(): void {
    this.activeConversationId.set(null);
    this.turns.set([]);
    this.sendError.set(null);
  }

  send(): void {
    const message = this.draft.trim();
    if (message.length === 0 || this.sending()) return;
    this.sending.set(true);
    this.sendError.set(null);
    // Optimistic user turn so the operator sees their message immediately.
    const userTurn: AssistantTurn = { role: 'user', content: message, ts: new Date().toISOString(), costUsd: 0 };
    this.turns.set([...this.turns(), userTurn]);
    this.draft = '';

    this.api
      .chat({ conversationId: this.activeConversationId(), userMessage: message })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (resp) => {
          this.activeConversationId.set(resp.conversationId);
          this.turns.set([...this.turns(), resp.turn]);
          this.sending.set(false);
          // Refresh the side panel so the new conversation surfaces.
          this.loadConversations();
        },
        error: (err: unknown) => {
          this.sending.set(false);
          this.sendError.set(errMsg(err));
        },
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
