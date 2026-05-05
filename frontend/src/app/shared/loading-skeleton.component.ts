import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-loading-skeleton',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-3" aria-busy="true" aria-live="polite">
      @for (_ of placeholderRows(); track $index) {
        <div class="skeleton h-4 w-full"></div>
      }
    </div>
  `,
})
export class LoadingSkeletonComponent {
  readonly rows = input(5);

  placeholderRows(): ReadonlyArray<number> {
    return Array.from({ length: this.rows() }, (_, i) => i);
  }
}
