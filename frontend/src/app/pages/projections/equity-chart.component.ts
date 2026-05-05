import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ProjectionResult } from '../../core/models/projection.model';
import { formatUsd } from '../../shared/format';

interface PercentilePoint {
  readonly week: number;
  readonly p10: number;
  readonly p50: number;
  readonly p90: number;
}

const W = 720;
const H = 240;
const PAD = { top: 16, right: 16, bottom: 24, left: 56 };

@Component({
  selector: 'app-equity-chart',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="glass-card">
      <div class="card-header">P10 / P50 / P90 — final equity percentiles</div>
      <p class="mb-4 text-xs text-fg-muted">
        The Monte Carlo engine returns aggregate percentiles, not full paths. We render the
        final-equity envelope as a single horizontal band: P10 floor, P50 median, P90 ceiling.
      </p>
      <svg [attr.viewBox]="viewBox" class="block w-full">
        <!-- Y-axis grid + labels -->
        @for (g of grid(); track g.y) {
          <line [attr.x1]="padLeft" [attr.x2]="W - padRight" [attr.y1]="g.y" [attr.y2]="g.y"
                stroke="rgba(148, 163, 184, 0.10)" stroke-width="1" />
          <text [attr.x]="padLeft - 6" [attr.y]="g.y + 3" text-anchor="end"
                font-size="10" fill="rgb(100 116 139)" font-family="ui-monospace, monospace">
            {{ g.label }}
          </text>
        }

        <!-- p10/p90 envelope -->
        <rect [attr.x]="bandX" [attr.y]="bandTopY()" [attr.width]="bandW"
              [attr.height]="bandHeight()" fill="rgb(59 130 246 / 0.15)" />

        <!-- p10 line -->
        <line [attr.x1]="bandX" [attr.x2]="bandX + bandW" [attr.y1]="lineY('p10')" [attr.y2]="lineY('p10')"
              stroke="rgb(239 68 68)" stroke-dasharray="4 4" stroke-width="1.5" />
        <text [attr.x]="bandX + bandW + 4" [attr.y]="lineY('p10') + 3" font-size="10" fill="rgb(239 68 68)"
              font-family="ui-monospace, monospace">P10</text>

        <!-- p50 line (median) -->
        <line [attr.x1]="bandX" [attr.x2]="bandX + bandW" [attr.y1]="lineY('p50')" [attr.y2]="lineY('p50')"
              stroke="rgb(96 165 250)" stroke-width="2" />
        <text [attr.x]="bandX + bandW + 4" [attr.y]="lineY('p50') + 3" font-size="10" fill="rgb(96 165 250)"
              font-family="ui-monospace, monospace">P50</text>

        <!-- p90 line -->
        <line [attr.x1]="bandX" [attr.x2]="bandX + bandW" [attr.y1]="lineY('p90')" [attr.y2]="lineY('p90')"
              stroke="rgb(34 197 94)" stroke-dasharray="4 4" stroke-width="1.5" />
        <text [attr.x]="bandX + bandW + 4" [attr.y]="lineY('p90') + 3" font-size="10" fill="rgb(34 197 94)"
              font-family="ui-monospace, monospace">P90</text>
      </svg>
    </div>
  `,
})
export class EquityChartComponent {
  readonly result = input.required<ProjectionResult>();
  readonly startingEquity = input.required<number>();

  readonly W = W;
  readonly padLeft = PAD.left;
  readonly padRight = PAD.right;
  readonly viewBox = `0 0 ${W} ${H}`;
  readonly bandX = PAD.left;
  readonly bandW = W - PAD.left - PAD.right;

  // Computed Y-axis bounds wrap min(p10, start) / max(p90, start, mean) so the
  // chart stays anchored to the starting equity even when the percentiles fall
  // entirely on one side. 10% padding either way.
  private readonly bounds = computed(() => {
    const r = this.result();
    const start = this.startingEquity();
    const lo = Math.min(r.p10, start) * 0.9;
    const hi = Math.max(r.p90, start, r.mean) * 1.1;
    return { lo, hi };
  });

  readonly grid = computed(() => {
    const { lo, hi } = this.bounds();
    const ticks = 5;
    return Array.from({ length: ticks }, (_, i) => {
      const v = lo + ((hi - lo) * i) / (ticks - 1);
      const y = this.yOf(v);
      return { y, label: formatUsd(v, 0) };
    });
  });

  bandTopY(): number {
    return Math.min(this.lineY('p90'), this.lineY('p50'), this.lineY('p10'));
  }

  bandHeight(): number {
    const ys = [this.lineY('p10'), this.lineY('p50'), this.lineY('p90')];
    return Math.max(...ys) - Math.min(...ys);
  }

  lineY(which: 'p10' | 'p50' | 'p90'): number {
    return this.yOf(this.result()[which]);
  }

  private yOf(v: number): number {
    const { lo, hi } = this.bounds();
    if (hi === lo) return (H - PAD.top - PAD.bottom) / 2 + PAD.top;
    const t = (v - lo) / (hi - lo);
    return H - PAD.bottom - t * (H - PAD.top - PAD.bottom);
  }
}
