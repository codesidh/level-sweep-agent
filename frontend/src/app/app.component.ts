import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { NavBarComponent } from './shared/nav-bar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavBarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="relative min-h-screen">
      <div class="glow-halo" aria-hidden="true"></div>
      <app-nav-bar />
      <main class="mx-auto max-w-screen px-6 py-12 lg:px-12">
        <router-outlet />
      </main>
      <footer class="mx-auto max-w-screen px-6 pb-12 pt-6 text-xs text-fg-muted lg:px-12">
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
          <span>LevelSweep Operator</span>
          <span class="opacity-50">·</span>
          <span>Phase A · read-only</span>
          <span class="opacity-50">·</span>
          <span>OWNER tenant</span>
          <span class="opacity-50">·</span>
          <span>BFF /api</span>
          <span class="ml-auto text-fg-muted/70">v0.1.0</span>
        </div>
      </footer>
    </div>
  `,
})
export class AppComponent {}
