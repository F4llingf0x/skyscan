import { Component, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink],
  template: `
    <header class="topbar">
      <a routerLink="/" class="brand">SKY<span>SCAN</span></a>
      <span class="dim mono">drone point-cloud service</span>
      <span class="spacer"></span>
      @if (auth.user(); as u) {
        <span class="mono dim">{{ u.displayName }} · <span class="badge" [class.amber]="u.role !== 'CUSTOMER'" [class.cyan]="u.role === 'CUSTOMER'">{{ u.role }}</span></span>
        <button (click)="auth.logout()">Sign out</button>
      }
    </header>
    <main><router-outlet /></main>
  `,
  styles: [`
    .topbar { display: flex; align-items: center; gap: 1rem; padding: .7rem 1.5rem;
      border-bottom: 1px solid var(--line); background: var(--bg-raised); }
    .brand { font-family: var(--font-display); font-size: 1.4rem; font-weight: 700;
      letter-spacing: .08em; color: var(--text); }
    .brand span { color: var(--amber); }
    .spacer { flex: 1; }
    main { padding: 1.5rem; max-width: 1280px; margin: 0 auto; }
  `],
})
export class AppComponent {
  auth = inject(AuthService);
}
