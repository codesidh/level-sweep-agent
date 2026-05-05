import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-nav-bar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="sticky top-0 z-30 px-6 pt-4 lg:px-12">
      <div class="mx-auto flex max-w-screen items-center justify-between gap-4">
        <!-- Brand mark -->
        <a routerLink="/" class="group flex items-center gap-3" aria-label="LevelSweep — home">
          <span
            class="grid h-9 w-9 place-items-center rounded-tile bg-gradient-to-br from-accent-blue to-accent-pink shadow-glow-blue"
            aria-hidden="true"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 17l4 0l3-7l4 4l4-8l3 0" />
            </svg>
          </span>
          <span class="hidden flex-col leading-tight sm:flex">
            <span class="text-sm font-semibold tracking-tight text-fg-primary">LevelSweep</span>
            <span class="text-[10px] font-medium uppercase tracking-widest text-fg-muted">Operator</span>
          </span>
        </a>

        <!-- Centered glass nav pill -->
        <nav
          class="glass hidden items-center gap-1 rounded-pill px-1.5 py-1.5 lg:flex"
          aria-label="Primary"
        >
          @for (link of links; track link.path) {
            <a
              class="nav-link"
              [routerLink]="link.path"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: link.path === '/' }"
            >
              {{ link.label }}
            </a>
          }
        </nav>

        <!-- Right side -->
        <div class="flex items-center gap-2">
          <span class="pill pill-warn hidden md:inline-flex">
            <span class="h-1.5 w-1.5 rounded-full bg-accent-amber" aria-hidden="true"></span>
            Phase A · OWNER
          </span>
          <span class="pill hidden md:inline-flex">
            <span class="h-1.5 w-1.5 rounded-full bg-accent-emerald" aria-hidden="true"></span>
            BFF live
          </span>
        </div>
      </div>

      <!-- Mobile nav (below lg) -->
      <nav
        class="glass mx-auto mt-3 flex max-w-screen items-center gap-1 overflow-x-auto rounded-pill px-1.5 py-1.5 lg:hidden"
        aria-label="Primary mobile"
      >
        @for (link of links; track link.path) {
          <a
            class="nav-link whitespace-nowrap"
            [routerLink]="link.path"
            routerLinkActive="active"
            [routerLinkActiveOptions]="{ exact: link.path === '/' }"
          >
            {{ link.label }}
          </a>
        }
      </nav>
    </header>
  `,
})
export class NavBarComponent {
  readonly links = [
    { path: '/', label: 'Dashboard' },
    { path: '/journal', label: 'Journal' },
    { path: '/narratives', label: 'Narratives' },
    { path: '/reviews', label: 'Reviews' },
    { path: '/projections', label: 'Projections' },
    { path: '/calendar', label: 'Calendar' },
    { path: '/assistant', label: 'Assistant' },
  ] as const;
}
