import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Site } from '../core/models';
import { SitesMapComponent } from './sites-map.component';

const VIEW_KEY = 'skyscan.dashboard.view';

@Component({
  standalone: true,
  imports: [RouterLink, SitesMapComponent],
  template: `
    <div class="head">
      <div>
        <h1>Sites</h1>
        <p class="dim">{{ auth.isStaff ? 'All customer sites.' : 'Your organization sites and deliverables.' }}</p>
      </div>
      <div class="toggle mono" role="group" aria-label="View mode">
        <button (click)="setView('tiles')" [class.btn-primary]="view() === 'tiles'">Tiles</button>
        <button (click)="setView('map')" [class.btn-primary]="view() === 'map'">Map</button>
      </div>
    </div>

    @if (view() === 'map') {
      <app-sites-map [sites]="sites()" (open)="openSite($event)" />
    } @else {
      <div class="grid">
        @for (s of sites(); track s.id) {
          <a class="card site" [routerLink]="['/sites', s.id]">
            <h2>{{ s.name }}</h2>
            <p class="dim">{{ s.description }}</p>
            <p class="mono dim">{{ s.organization }} · {{ s.centerLat.toFixed(4) }}, {{ s.centerLon.toFixed(4) }}</p>
          </a>
        } @empty {
          <p class="dim">No sites yet.</p>
        }
      </div>
    }
  `,
  styles: [`
    .head { display: flex; justify-content: space-between; align-items: start; gap: 1rem; }
    .toggle { display: flex; gap: 2px; }
    .toggle button { font-size: .72rem; padding: .35rem .9rem; }
    .toggle button:first-child { border-radius: 4px 0 0 4px; }
    .toggle button:last-child { border-radius: 0 4px 4px 0; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 1rem; }
    .site { display: block; color: var(--text); }
    .site:hover { border-color: var(--amber); text-decoration: none; }
  `],
})
export class DashboardComponent {
  auth = inject(AuthService);
  private router = inject(Router);
  sites = toSignal(inject(ApiService).sites(), { initialValue: [] });
  view = signal<'tiles' | 'map'>(
    (localStorage.getItem(VIEW_KEY) as 'tiles' | 'map') ?? 'tiles');

  setView(v: 'tiles' | 'map') {
    this.view.set(v);
    localStorage.setItem(VIEW_KEY, v);
  }

  openSite(s: Site) { this.router.navigate(['/sites', s.id]); }
}
