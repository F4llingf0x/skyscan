import { Component, OnDestroy, OnInit, inject, signal, computed } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Asset, HAPPY_PATH, Mission, MissionState, ScanAreaInfo, Telemetry } from '../core/models';
import { MapDrawComponent } from './map-draw.component';
import { CloudEditorComponent } from '../viewer/cloud-editor.component';
import { LasCloud, loadLas } from '../viewer/las-loader';

@Component({
  standalone: true,
  imports: [FormsModule, RouterLink, DecimalPipe, MapDrawComponent, CloudEditorComponent],
  template: `
    @if (mission(); as m) {
      <div class="head">
        <div>
          <h1>{{ m.name }}</h1>
          <p class="dim">Site: <a [routerLink]="['/sites', m.siteId]">{{ m.siteName }}</a>
            · <span class="badge" [class.amber]="m.scanMode === 'LANDSCAPE'" [class.cyan]="m.scanMode === 'STRUCTURE'">
              {{ m.scanMode === 'STRUCTURE' ? 'STRUCTURE · FRONT 360° VERTICAL' : 'LANDSCAPE · DOWNWARD ±30°' }}</span></p>
        </div>
        @if (auth.isStaff && abortable()) {
          <button class="btn-danger" (click)="abort()">Abort · return to home</button>
        }
      </div>

      <!-- flight-progress strip -->
      <div class="strip" aria-label="Mission progress">
        @for (s of happyPath; track s) {
          <div class="seg" [class.done]="isDone(s)" [class.now]="m.state === s">{{ label(s) }}</div>
        }
        @if (m.state === 'FAILED' || m.state === 'ABORTED' || m.state === 'RETURN_TO_HOME') {
          <div class="seg bad now">{{ m.state }}</div>
        }
      </div>
      @if (m.lastError) { <p class="mono" style="color: var(--red)">{{ m.lastError }}</p> }

      @if (canRefine()) {
        <div class="card" style="margin-top:1rem">
          <h2>{{ isRescan() ? 'Rescan selector' : 'Detailed flight selector' }}
            <span class="badge cyan">{{ isRescan() ? 'send the drone back' : 'step 2 of 2' }}</span></h2>
          <p class="dim">{{ isRescan()
            ? 'Select the area to re-scan directly on the DELIVERED point cloud (or on the map) — the drone flies back, scans it, and the new pass is merged into the deliverable.'
            : 'Select the detail zone on the crude point cloud or on the map below — the two stay in sync. Then plan the route and launch.' }}</p>
          @if (crudeCloud(); as c) {
            <app-cloud-editor [cloud]="c" [crs]="crudeCrs()"
              [zoneGeoJson]="detailPolygon()"
              [routeGeoJson]="detailRoute()"
              [routeAltitudeM]="altitudeM"
              (polygon)="onDetailPolygon($event)" />
          } @else {
            <p class="mono dim">{{ cloudStatus() }}</p>
          }
        </div>
      }

      <div class="layout">
        <div>
          <app-map-draw
            [overlays]="overlays()"
            [drawEnabled]="canRefine()"
            drawColor="#5cc8ff"
            [vehicle]="vehiclePos()"
            (polygon)="onDetailPolygon($event)" />

          <!-- telemetry readout -->
          @if (telemetry(); as t) {
            <div class="card tele mono">
              <span>ALT <b>{{ t.altM | number:'1.1-1' }} m</b></span>
              <span>SPD <b>{{ t.speedMps | number:'1.1-1' }} m/s</b></span>
              <span>BAT <b>{{ t.batteryPct | number:'1.0-0' }}%</b></span>
              <span>MODE <b>{{ t.flightMode }}</b></span>
              <span>PROGRESS <b>{{ t.progressPct | number:'1.0-0' }}%</b></span>
            </div>
          }
        </div>

        <div class="side">
          <!-- field preview + refine -->
          @if (previewUrl()) {
            <div class="card">
              <h2>Field preview <span class="badge amber">crude pass</span></h2>
              <img [src]="previewUrl()" alt="Downsampled preview of the crude scan" class="preview">
            </div>
          }

          @if (canRefine()) {
            <div class="card">
              <h2>Detailed pass <span class="badge cyan">plan &amp; launch</span></h2>
              <div class="field"><label for="mode2">Mounted payload / scan mode</label>
                <select id="mode2" name="mode2" [(ngModel)]="scanMode" (ngModelChange)="onModeChange()">
                  <option value="LANDSCAPE">Landscape — downward ±30° tilt</option>
                  <option value="STRUCTURE">Structure — front 360° vertical (buildings)</option>
                </select></div>
              <div class="field"><label for="alt2">{{ scanMode === 'STRUCTURE' ? 'Structure top height (m)' : 'Altitude (m AGL)' }}</label>
                <input id="alt2" name="alt2" type="number" [(ngModel)]="altitudeM"></div>
              <div class="field"><label for="ls2">{{ scanMode === 'STRUCTURE' ? 'Orbit level spacing (m)' : 'Line spacing (m)' }}</label>
                <input id="ls2" name="ls2" type="number" [(ngModel)]="lineSpacingM"></div>
              @if (scanMode === 'STRUCTURE') {
                <div class="field"><label for="so">Standoff from facade (m)</label>
                  <input id="so" name="so" type="number" [(ngModel)]="standoffM"></div>
              }
              <div class="field"><label for="td">Ring tilt — fixed for this flight</label>
                <select id="td" name="td" [(ngModel)]="tiltDeg">
                  <option [ngValue]="30">+30° — facade side looks up (eaves, overhangs, roofline)</option>
                  <option [ngValue]="0">0° — level slice at flight altitude</option>
                  <option [ngValue]="-30">−30° — facade side looks down (terrain, plinths, roofs from above)</option>
                </select></div>
              <div class="field"><label for="sp2">Speed (m/s)</label>
                <input id="sp2" name="sp2" type="number" [(ngModel)]="speedMps"></div>
              @if (scanMode === 'LANDSCAPE') {
                <div class="field"><label for="tf" style="display:inline">Terrain following</label>
                  <input id="tf" name="tf" type="checkbox" [(ngModel)]="terrainFollowing" style="width:auto; margin-left:.5rem"></div>
              }

              <button [disabled]="!detailPolygon() || busy()" (click)="planRoute()">
                {{ detailRoute() ? 'Re-plan route' : 'Plan optimal route' }}
              </button>
              @if (routeStats(); as r) {
                <p class="mono dim" style="margin:.6rem 0">
                  Route: {{ r.lines }} {{ scanMode === 'STRUCTURE' ? 'orbit levels' : 'lines' }} ·
                  {{ r.lengthM | number:'1.0-0' }} m ·
                  ≈{{ r.durationS / 60 | number:'1.0-1' }} min — shown in amber on the cloud and map.
                </p>
              }
              @if (refineError()) { <p class="mono" style="color: var(--red)">{{ refineError() }}</p> }
              <button class="btn-primary" style="margin-top:.4rem"
                [disabled]="m.state !== 'DETAIL_SELECTED' || !detailRoute() || busy()" (click)="launchDetail()">
                {{ isRescan() ? 'Launch rescan flight' : 'Launch detailed scan' }}
              </button>
            </div>
          }

          <!-- pre-flight self-test (remote diagnostics) -->
          @if (auth.isStaff) {
            <div class="card">
              <h2>Pre-flight check <span class="badge amber">remote</span></h2>
              <button (click)="runSelfTest()" [disabled]="testRunning()">
                {{ testRunning() ? 'Running on drone…' : 'Run self-test' }}
              </button>
              @if (selfTest(); as st) {
                <p class="mono" style="margin:.6rem 0 .3rem">
                  Overall:
                  <span class="badge" [class.green]="st.overall === 'PASS'"
                        [class.amber]="st.overall === 'WARN'" [class.red]="st.overall === 'FAIL'">
                    {{ st.overall }}</span>
                  <span class="dim"> · {{ st.mode }} · {{ st.checks.length }} checks</span>
                </p>
                <div class="checks">
                  @for (c of st.checks; track c.component) {
                    <div class="check" [class.bad]="c.status === 'FAIL'">
                      <span class="badge" [class.green]="c.status === 'PASS'"
                            [class.amber]="c.status === 'WARN'" [class.red]="c.status === 'FAIL'">
                        {{ c.status }}</span>
                      <span class="mono comp">{{ c.component }}</span>
                      <span class="mono dim val">{{ c.value }}</span>
                      <div class="msg">{{ c.message }}
                        @if (c.hint) { <span class="dim"> — {{ c.hint }}</span> }
                      </div>
                    </div>
                  }
                </div>
              }
            </div>
          }

          <!-- deliverables -->
          <div class="card">
            <h2>Deliverables</h2>
            @for (a of assets(); track a.id) {
              <div class="asset">
                <div>{{ a.name }}
                  <span class="badge" [class.green]="a.status === 'READY'" [class.red]="a.status !== 'READY'">
                    {{ a.status === 'READY' ? 'PUBLISHED' : 'IN REVIEW' }}</span>
                </div>
                <div class="mono dim">{{ a.pointCount | number }} pts · {{ a.crs }}</div>
                <a class="btn" [routerLink]="['/assets', a.id]">
                  {{ auth.isStaff && a.status !== 'READY' ? 'Review & publish' : 'Open viewer' }}</a>
              </div>
            } @empty {
              <p class="dim">{{ m.state === 'PROCESSING' ? 'Processing in the C++ engine…' : 'Nothing delivered yet.' }}</p>
            }
          </div>

          @if (photos().length) {
            <div class="card">
              <h2>Flight photos <span class="badge amber">camera</span></h2>
              <div class="gallery">
                @for (ph of photos(); track ph.name) {
                  <a [href]="ph.url" target="_blank" rel="noopener">
                    <img [src]="ph.url" [alt]="ph.name" loading="lazy"></a>
                }
              </div>
            </div>
          }

          @if (auth.isStaff && flightLogs().length) {
            <div class="card">
              <h2>Flight logs <span class="badge amber">full detail</span></h2>
              @for (l of flightLogs(); track l.name) {
                <div class="mono" style="padding:.25rem 0"><a [href]="l.url" download>{{ l.name }}</a></div>
              }
            </div>
          }

          <div class="card">
            <h2>Event log</h2>
            <div class="log mono">
              @for (e of log(); track $index) { <div>{{ e }}</div> }
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .head { display: flex; justify-content: space-between; align-items: start; }
    .layout { display: grid; grid-template-columns: 1fr 360px; gap: 1rem; margin-top: 1rem; align-items: start; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } }
    .side { display: grid; gap: 1rem; }
    .tele { display: flex; gap: 1.2rem; flex-wrap: wrap; margin-top: .6rem; padding: .6rem 1rem; }
    .tele b { color: var(--amber); }
    .preview { width: 100%; border-radius: 4px; border: 1px solid var(--line); }
    .asset { padding: .5rem 0; border-bottom: 1px solid var(--line); display: grid; gap: .25rem; }
    .log { max-height: 220px; overflow-y: auto; font-size: .72rem; color: var(--text-dim); }
    .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: .4rem; }
    .gallery img { width: 100%; aspect-ratio: 4/3; object-fit: cover; border-radius: 4px;
      border: 1px solid var(--line); }
    .gallery img:hover { border-color: var(--amber); }
    .checks { display: grid; gap: .35rem; max-height: 320px; overflow-y: auto; }
    .check { display: grid; grid-template-columns: 54px 130px 1fr; gap: .4rem .6rem;
      align-items: baseline; padding: .3rem .4rem; border: 1px solid var(--line); border-radius: 4px; }
    .check.bad { border-color: var(--red); }
    .check .comp { font-size: .72rem; }
    .check .val { font-size: .7rem; text-align: right; }
    .check .msg { grid-column: 1 / -1; font-size: .78rem; }
  `],
})
export class MissionComponent implements OnInit, OnDestroy {
  auth = inject(AuthService);
  private api = inject(ApiService);
  private id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  happyPath = HAPPY_PATH;
  mission = signal<Mission | null>(null);
  areas = signal<ScanAreaInfo[]>([]);
  telemetry = signal<Telemetry | null>(null);
  assets = signal<Asset[]>([]);
  previewUrl = signal('');
  log = signal<string[]>([]);
  photos = signal<Array<{ url: string; name: string }>>([]);
  selfTest = signal<any | null>(null);
  testRunning = signal(false);
  flightLogs = signal<Array<{ url: string; name: string }>>([]);
  detailPolygon = signal('');
  crudeCloud = signal<LasCloud | null>(null);
  crudeCrs = signal('EPSG:32634');
  cloudStatus = signal('Loading crude point cloud from object storage…');
  detailRoute = signal('');
  routeStats = signal<{ lines: number; lengthM: number; durationS: number } | null>(null);
  refineError = signal('');
  busy = signal(false);

  altitudeM = 35;
  lineSpacingM = 8;
  speedMps = 4;
  terrainFollowing = true;
  scanMode = 'LANDSCAPE';
  standoffM = 15;
  tiltDeg = 0;

  private es?: EventSource;

  overlays = computed<Array<[string, string]>>(() => {
    const base: Array<[string, string]> = this.areas()
      .map(a => [a.polygonGeoJson, a.passType === 'CRUDE' ? '#ffb454' : '#5cc8ff'] as [string, string]);
    for (const a of this.areas())
      if (a.routeJson) base.push([a.routeJson, '#7ad48a']);                   // planned/flown routes
    if (this.detailPolygon()) base.push([this.detailPolygon(), '#5cc8ff']);   // map reference of the 3D selection
    if (this.detailRoute()) base.push([this.detailRoute(), '#ffb454']);       // freshly planned route
    return base;
  });
  vehiclePos = computed(() => {
    const t = this.telemetry();
    return t ? { lat: t.lat, lon: t.lon } : null;
  });
  canRefine = computed(() =>
    this.auth.isStaff && ['PREVIEW_READY', 'DETAIL_SELECTED', 'COMPLETE'].includes(this.mission()?.state ?? ''));
  isRescan = computed(() => this.mission()?.state === 'COMPLETE' || this.rescanArmed());
  rescanArmed = signal(false);
  abortable = computed(() =>
    ['CRUDE_MISSION_UPLOADED', 'CRUDE_FLYING', 'DETAIL_MISSION_UPLOADED', 'DETAIL_FLYING']
      .includes(this.mission()?.state ?? ''));

  async ngOnInit() {
    await this.reload();
    this.subscribe();
  }

  private async reload() {
    const res = await firstValueFrom(this.api.mission(this.id));
    this.mission.set(res.mission);
    if (res.mission.scanMode) this.scanMode = res.mission.scanMode;
    this.areas.set(res.areas);
    const detail = (res.areas as ScanAreaInfo[]).find(a => a.passType === 'DETAILED');
    if (detail) {
      if (detail.polygonGeoJson) this.detailPolygon.set(detail.polygonGeoJson);
      if (detail.routeJson) this.detailRoute.set(detail.routeJson);
    }
    this.assets.set(await firstValueFrom(this.api.deliverables(this.id)));
    this.api.missionPhotos(this.id).subscribe({ next: p => this.photos.set(p), error: () => {} });
    this.api.latestSelfTest(this.id).subscribe({
      next: r => this.selfTest.set(this.parseReport(r.report)), error: () => {} });
    this.api.flightLogs(this.id).subscribe({ next: l => this.flightLogs.set(l), error: () => {} });
    this.tryLoadPreview(res.mission.state);
    this.tryLoadCrudeCloud(res.mission.state);
  }

  private subscribe() {
    this.es = this.api.missionEvents(this.id);
    this.es.addEventListener('state', (e: MessageEvent) => {
      const data = JSON.parse(e.data);
      this.appendLog(`${data.at ?? ''}  STATE → ${data.state}  ${data.note ?? ''}`);
      const m = this.mission();
      if (m) this.mission.set({ ...m, state: data.state as MissionState });
      this.tryLoadPreview(data.state);
      this.tryLoadCrudeCloud(data.state);
      if (data.state === 'COMPLETE') { this.crudeCloud.set(null); this.reload(); }
    });
    this.es.addEventListener('selftest', (e: MessageEvent) => {
      this.selfTest.set(this.parseReport(e.data));
      this.testRunning.set(false);
      this.appendLog('Self-test report received');
    });
    this.es.addEventListener('flightlog', () => {
      this.api.flightLogs(this.id).subscribe({ next: l => this.flightLogs.set(l), error: () => {} });
      this.appendLog('Flight log uploaded by the drone');
    });
    this.es.addEventListener('agent', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        this.appendLog(`AGENT ${d.type}: ${d.payload}`);
      } catch { /* ignore */ }
    });
    this.es.addEventListener('telemetry', (e: MessageEvent) => {
      const t: Telemetry = JSON.parse(e.data);
      this.telemetry.set(t);
    });
  }

  /** Payload changed: sensible defaults per mode; existing route becomes stale. */
  runSelfTest() {
    this.testRunning.set(true);
    this.api.runSelfTest(this.id).subscribe({
      next: () => this.appendLog('Self-test requested — running on the drone'),
      error: () => { this.testRunning.set(false); this.appendLog('Self-test request failed'); },
    });
    // safety timeout in case no report arrives
    setTimeout(() => this.testRunning.set(false), 30000);
  }

  /** Report may arrive JSON-encoded once or twice (SSE string payload). */
  private parseReport(raw: string): any {
    try {
      let v: any = JSON.parse(raw);
      if (typeof v === 'string') v = JSON.parse(v);
      return v;
    } catch { return null; }
  }

  onModeChange() {
    if (this.scanMode === 'STRUCTURE') { this.altitudeM = 20; this.lineSpacingM = 4; this.tiltDeg = 0; }
    else { this.altitudeM = 35; this.lineSpacingM = 8; this.tiltDeg = -30; }
    this.detailRoute.set('');
    this.routeStats.set(null);
  }

  onDetailPolygon(geojson: string) {
    if (geojson === this.detailPolygon()) return;
    this.detailPolygon.set(geojson);
    this.detailRoute.set('');       // zone changed -> previous route is stale
    this.routeStats.set(null);
    this.appendLog('Detail zone updated — plan the route to continue');
  }

  private tryLoadCrudeCloud(state: string) {
    if (this.crudeCloud()) return;
    if (state === 'COMPLETE') {
      // Rescan mode: select on the DELIVERED merged cloud for full fidelity.
      this.api.deliverables(this.id).subscribe({
        next: assets => {
          const latest = assets[0];
          if (!latest) { this.cloudStatus.set('No deliverable to rescan from yet.'); return; }
          this.crudeCrs.set(latest.crs || this.crudeCrs());
          this.api.assetUrls(latest.id).subscribe({
            next: async u => {
              try { this.crudeCloud.set(await loadLas(u.lasUrl)); this.rescanArmed.set(true); }
              catch (e: any) { this.cloudStatus.set('Could not stream the merged cloud: ' + (e?.message ?? e)); }
            },
            error: () => this.cloudStatus.set('Deliverable not accessible.'),
          });
        },
        error: () => {},
      });
      return;
    }
    if (!['PREVIEW_READY', 'DETAIL_SELECTED'].includes(state)) return;
    this.api.crudeCloud(this.id).subscribe({
      next: async r => {
        this.crudeCrs.set(r.crs);
        try {
          this.crudeCloud.set(await loadLas(r.cloudUrl));
        } catch (e: any) {
          this.cloudStatus.set('Could not stream the crude cloud: ' + (e?.message ?? e));
        }
      },
      error: () => this.cloudStatus.set('Crude cloud not available yet.'),
    });
  }

  private tryLoadPreview(state: string) {
    const after = HAPPY_PATH.indexOf('PREVIEW_READY');
    if (HAPPY_PATH.indexOf(state as MissionState) >= after && !this.previewUrl()) {
      this.api.preview(this.id).subscribe({
        next: r => this.previewUrl.set(r.previewUrl),
        error: () => {},
      });
    }
  }

  /** Submit the refined zone: SkyScan computes the optimal coverage route. */
  async planRoute() {
    this.busy.set(true); this.refineError.set('');
    try {
      const res = await firstValueFrom(this.api.refine(this.id, {
        polygonGeoJson: this.detailPolygon(), altitudeM: this.altitudeM,
        lineSpacingM: this.lineSpacingM, speedMps: this.speedMps,
        terrainFollowing: this.terrainFollowing,
        scanMode: this.scanMode, standoffM: this.standoffM, tiltDeg: this.tiltDeg,
      }));
      this.mission.set(res.mission);
      this.detailRoute.set(res.routeGeoJson);
      this.routeStats.set({ lines: res.routeLineCount, lengthM: res.routeLengthM, durationS: res.estDurationS });
      this.appendLog(`Route planned: ${res.routeLineCount} lines, ${Math.round(res.routeLengthM)} m`);
    } catch (e: any) {
      this.refineError.set(e?.error?.message ?? 'Route planning failed — check zone and line spacing.');
    } finally { this.busy.set(false); }
  }

  async launchDetail() {
    this.busy.set(true);
    try {
      await firstValueFrom(this.api.launchDetail(this.id));
      this.appendLog('Detailed scan launched along the planned route');
      await this.reload();
    } finally { this.busy.set(false); }
  }

  async abort() {
    await firstValueFrom(this.api.abort(this.id, 'operator abort from console'));
  }

  isDone(s: MissionState): boolean {
    const cur = this.mission()?.state;
    if (!cur) return false;
    return HAPPY_PATH.indexOf(s) < HAPPY_PATH.indexOf(cur as MissionState);
  }

  label(s: string): string { return s.replaceAll('_', ' '); }

  private appendLog(line: string) { this.log.set([line, ...this.log()].slice(0, 200)); }

  ngOnDestroy(): void { this.es?.close(); }
}
