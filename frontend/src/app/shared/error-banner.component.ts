import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'app-error-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="rounded-tile border border-accent-red/40 bg-accent-red/10 p-4 text-sm backdrop-blur-md"
      role="alert"
    >
      <div class="flex items-start justify-between gap-3">
        <div class="flex items-start gap-3">
          <span class="mt-0.5 grid h-7 w-7 place-items-center rounded-full bg-accent-red/20" aria-hidden="true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="text-accent-red">
              <line x1="12" y1="9" x2="12" y2="13" />
              <circle cx="12" cy="17" r="0.5" fill="currentColor" />
              <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
            </svg>
          </span>
          <div>
            <div class="font-semibold text-accent-red">{{ title() }}</div>
            <div class="mt-0.5 text-fg-secondary">{{ message() }}</div>
          </div>
        </div>
        @if (canRetry()) {
          <button class="btn shrink-0" type="button" (click)="retryClick.emit()">
            Retry
          </button>
        }
      </div>
    </div>
  `,
})
export class ErrorBannerComponent {
  readonly title = input('Request failed');
  readonly message = input<string>('Something went wrong.');
  readonly canRetry = input(true);
  readonly retryClick = output<void>();
}
