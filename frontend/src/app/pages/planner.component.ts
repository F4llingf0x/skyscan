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
          <div class="field"><label for="mt">Mission type</label>
            <select id="mt" name="mt" [(ngModel)]="missionType">
              <option value="SURVEY_3D">3D survey — LiDAR point cloud (two-pass)</option>
              <option value="CROP_HEALTH">Crop health — detect plants, then inspect for disease</option>
              <option value="WILDLIFE_SURVEY">Wildlife survey — find and tag animals (RGB + thermal)</option>
            </select></div>
          @if (missionType === 'SURVEY_3D') {
            <div class="field"><label for="mode">Mounted payload / scan mode</label>
              <select id="mode" name="mode" [(ngModel)]="scanMode">
                <option value="LANDSCAPE">Landscape — downward ±30° tilt (terrain coverage)</option>
                <option value="STRUCTURE">Structure — front-facing 360° vertical, ±30° (buildings, statues)</option>
              </select></div>
          } @else {
            @if (missionType === 'CROP_HEALTH') {
              <p class="mono dim">Crop health: the crude pass maps every crop plant and tree in
                the polygon; you then review them and launch a per-plant disease inspection
                (battery swaps between plants are handled automatically).</p>
            } @else {
              <p class="mono dim">Wildlife survey: a single coverage pass over the polygon —
                the RGB and thermal cameras feed the on-board detector, and every animal is
                tagged with species, time and location the moment it is found.</p>
              <div class="field"><label for="env">Terrain</label>
                <select id="env" name="env" [(ngModel)]="surveyEnvironment">
                  <option value="OPEN">Open field — RGB + thermal fusion</option>
                  <option value="FOREST">Forest — thermal-primary (canopy gaps)</option>
                </select></div>
              @if (surveyEnvironment === 'FOREST') {
                <p class="mono dim">Under canopy, warm bodies glimpsed through gaps are the
                  signal: thermal-only tags are treated as first-class and re-glimpses of the
                  same animal merge. Fly at dawn/dusk for maximum thermal contrast; in
                  deciduous forest, leaf-off season gives far better detection. Expect
                  species to read "unidentified (warm body)" more often than in the open.</p>
              }
            }
            <div class="field"><label for="cam">Mounted camera module</label>
              <select id="cam" name="cam" [(ngModel)]="cameraModule">
                <option value="imx219-77">IMX219 — 77° diagonal (default)</option>
                <option value="imx219-std">IMX219 — stock lens (62.2° horizontal)</option>
                <option value="imx219-120">IMX219 — 120° wide (degraded mapping accuracy)</option>
                <option value="imx219-160">IMX219 — 160° fisheye (NOT for plant mapping)</option>
              </select></div>
            <p class="mono dim">Must match the physically mounted lens — the module's field of
              view drives where detected plants are placed on the map.</p>
          }
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
  missionType = 'SURVEY_3D';
  cameraModule = 'imx219-77';
  surveyEnvironment = 'OPEN';
  altitudeM = 80;
  lineSpacingM = 25;
  speedMps = 8;
  busy = signal(false);
  error = signal('');

  async launch() {
    this.busy.set(true); this.error.set('');
    if (this.missionType === 'CROP_HEALTH') this.scanMode = 'LANDSCAPE';
    try {
      const mission = await firstValueFrom(this.api.createMission({
        name: this.name, siteId: this.siteId, polygonGeoJson: this.polygonGeoJson(),
        altitudeM: this.altitudeM, lineSpacingM: this.lineSpacingM, speedMps: this.speedMps,
        scanMode: this.scanMode, missionType: this.missionType,
        cameraModule: this.missionType !== 'SURVEY_3D' ? this.cameraModule : undefined,
        surveyEnvironment: this.missionType === 'WILDLIFE_SURVEY' ? this.surveyEnvironment : undefined,
      }));
      await firstValueFrom(this.api.launchCrude(mission.id));
      this.router.navigate(['/missions', mission.id]);
    } catch (e: any) {
      this.error.set(e?.error?.message ?? 'Launch failed.');
      this.busy.set(false);
    }
  }
}
