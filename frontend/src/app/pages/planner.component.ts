import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { MapDrawComponent } from './map-draw.component';

@Component({
  standalone: true,
  imports: [FormsModule, MapDrawComponent],
  template: `
    @if (site(); as s) {
      <h1>Plan mission — {{ s.name }}</h1>
      <p class="dim">Draw the crude survey polygon on the map, set the coarse-pass parameters, then launch.</p>
      <div class="layout">
        <app-map-draw
          [center]="[s.centerLat, s.centerLon]"
          [drawEnabled]="true"
          drawColor="#ffb454"
          (polygon)="polygonGeoJson.set($event)" />

        <div class="card">
          <h2>Crude pass parameters</h2>
          <div class="field"><label for="n">Mission name</label>
            <input id="n" name="n" [(ngModel)]="name"></div>
          <div class="field"><label for="mode">Mounted payload / scan mode</label>
            <select id="mode" name="mode" [(ngModel)]="scanMode">
              <option value="LANDSCAPE">Landscape — downward ±30° tilt (terrain coverage)</option>
              <option value="STRUCTURE">Structure — front-facing 360° vertical, ±30° (buildings, statues)</option>
            </select></div>
          <div class="field"><label for="alt">Altitude (m AGL)</label>
            <input id="alt" name="alt" type="number" [(ngModel)]="altitudeM"></div>
          <div class="field"><label for="ls">Line spacing (m)</label>
            <input id="ls" name="ls" type="number" [(ngModel)]="lineSpacingM"></div>
          <div class="field"><label for="sp">Speed (m/s)</label>
            <input id="sp" name="sp" type="number" [(ngModel)]="speedMps"></div>
          <p class="mono dim">{{ polygonGeoJson() ? 'Scan area captured.' : 'No polygon drawn yet — use the polygon tool on the map.' }}</p>
          @if (error()) { <p class="mono" style="color: var(--red)">{{ error() }}</p> }
          <button class="btn-primary" [disabled]="!polygonGeoJson() || busy()" (click)="launch()">
            Launch crude scan
          </button>
        </div>
      </div>
    }
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 1fr 340px; gap: 1rem; align-items: start; }
    @media (max-width: 900px) { .layout { grid-template-columns: 1fr; } }
  `],
})
export class PlannerComponent {
  private api = inject(ApiService);
  private router = inject(Router);
  private siteId = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  site = toSignal(this.api.site(this.siteId));
  polygonGeoJson = signal('');
  name = 'Survey ' + new Date().toISOString().slice(0, 10);
  scanMode = 'LANDSCAPE';
  altitudeM = 80;
  lineSpacingM = 25;
  speedMps = 8;
  busy = signal(false);
  error = signal('');

  async launch() {
    this.busy.set(true); this.error.set('');
    try {
      const mission = await firstValueFrom(this.api.createMission({
        name: this.name, siteId: this.siteId, polygonGeoJson: this.polygonGeoJson(),
        altitudeM: this.altitudeM, lineSpacingM: this.lineSpacingM, speedMps: this.speedMps,
        scanMode: this.scanMode,
      }));
      await firstValueFrom(this.api.launchCrude(mission.id));
      this.router.navigate(['/missions', mission.id]);
    } catch (e: any) {
      this.error.set(e?.error?.message ?? 'Launch failed.');
      this.busy.set(false);
    }
  }
}
