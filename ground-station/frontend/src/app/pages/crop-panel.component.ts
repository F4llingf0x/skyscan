import { Component, Input, OnInit, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { MapDrawComponent } from './map-draw.component';
import { Mission, Plant } from '../core/models';

/**
 * CROP_HEALTH side panel for the mission page. Three states:
 *  - PREVIEW_READY: show detected plants on the map + a table; operator sets
 *    inspection altitude/speed and launches the per-plant disease pass.
 *  - DETAIL_FLYING / PROCESSING: inspection in progress.
 *  - COMPLETE: link to the findings report + per-plant findings summary.
 */
@Component({
  selector: 'app-crop-panel',
  standalone: true,
  imports: [FormsModule, DecimalPipe, MapDrawComponent],
  template: `
    <div class="card">
      <h2>Crop health
        <span class="badge" [class.amber]="!done()" [class.green]="done()">
          {{ done() ? 'report ready' : 'agronomy' }}</span></h2>

      @if (plants().length) {
        <app-map-draw
          [center]="center"
          [drawEnabled]="rescanArmed()"
          drawColor="#ff6b5e"
          (polygon)="rescanPolygon.set($event)"
          [markers]="plantMarkers()" />
        <div class="tally mono">
          {{ crops() }} crop plants · {{ trees() }} trees
          @if (zones()) { · {{ zones() }} field zones }
          @if (mission.cameraModule) { · cam {{ mission.cameraModule }} }
          @if (done()) { · {{ affected() }} with findings }
        </div>
      } @else {
        <p class="dim">No plants detected yet.</p>
      }

      @if (canLaunch()) {
        <div class="rescan">
          @if (!rescanArmed()) {
            <button (click)="rescanArmed.set(true)">Missed plants? Rescan an area…</button>
            <p class="mono dim">If the map is missing plants, send the drone back over a
              sub-area — new finds merge into this list, and you return here to verify
              (repeat until complete), then launch the inspection.</p>
          } @else {
            <p class="mono">Draw the area to rescan on the map (red), then:</p>
            <div class="field"><label>Rescan altitude (m AGL)</label>
              <input type="number" [(ngModel)]="rescanAltM" name="ra"></div>
            <button class="btn-primary" [disabled]="!rescanPolygon() || busy()"
                    (click)="flyRescan()">Fly detection rescan</button>
            <button (click)="rescanArmed.set(false); rescanPolygon.set('')">Cancel</button>
            @if (error()) { <p class="mono" style="color:var(--red)">{{ error() }}</p> }
          }
        </div>

        <div class="field"><label>Inspection altitude (m AGL)</label>
          <input type="number" [(ngModel)]="inspectAltM" name="ia"></div>
        <div class="field"><label>Speed (m/s)</label>
          <input type="number" [(ngModel)]="speedMps" name="isp"></div>
        <p class="mono dim">The drone visits each of the {{ plants().length }} plants, hovers
          for close-ups, and returns to swap batteries as needed — the pass resumes at the
          next unvisited plant.</p>
        @if (error()) { <p class="mono" style="color:var(--red)">{{ error() }}</p> }
        <button class="btn-primary" [disabled]="busy() || included() === 0" (click)="launch()">
          Launch disease inspection ({{ included() }} of {{ plants().length }} plants)</button>
      }

      @if (inProgress()) {
        <p class="dim">Inspection in progress — visiting each plant…</p>
      }

      @if (done()) {
        <a class="btn-primary" [href]="reportUrl()" target="_blank" rel="noopener"
           [class.disabled]="!reportUrl()">Open crop health report</a>
        <button style="margin-left:.5rem" [disabled]="busy()" (click)="createFollowUp()">
          Plan follow-up inspection</button>
        <p class="mono dim">A follow-up re-checks the SAME plants months later — no new
          crude pass; the report shows what is new, persisting, or resolved.</p>
      }
    </div>

    @if (plants().length) {
      <div class="card">
        <h2>Detected plants</h2>
        <div class="plants">
          <table>
            <tr>
              @if (canLaunch()) { <th title="Include in the inspection flight">Fly</th> }
              <th>Plant</th><th>Kind</th><th>Vigor</th><th>Photo</th>
              @if (done()) { <th>Findings</th> }</tr>
            @for (p of plants(); track p.id) {
              <tr [class.excludedRow]="p.excluded">
                @if (canLaunch()) {
                  <td><input type="checkbox" [checked]="!p.excluded"
                             (change)="toggleExclude(p)" [disabled]="busy()"></td>
                }
                <td class="mono">{{ p.label }}</td>
                <td>{{ p.kind === 'CROP_FIELD' ? ('ZONE Ø' + (p.canopyDiaM | number:'1.0-0') + ' m') : p.kind }}</td>
                <td><span class="vig" [style.--v]="p.healthIndex">{{ p.healthIndex | number:'1.2-2' }}</span></td>
                <td>
                  @if (p.photoUrl) {
                    <a [href]="p.photoUrl" target="_blank" rel="noopener">
                      <img [src]="p.photoUrl" class="thumb" alt="{{ p.label }}"></a>
                  } @else { <span class="dim">—</span> }
                </td>
                @if (done()) {
                  <td>
                    @if (p.status === 'SKIPPED' || p.status === 'EXCLUDED') {
                      <span class="dim">skipped</span>
                    } @else if (p.findings.length) {
                      @for (f of p.findings; track f.diseaseId) {
                        <span class="sev sev-{{ f.severity }}">{{ f.diseaseName }}</span>
                      }
                    } @else { <span class="dim">healthy</span> }
                  </td>
                }
              </tr>
            }
          </table>
        </div>
      </div>
    }
  `,
  styles: [`
    .tally { margin:.5rem 0; color: var(--text); }
    .plants { max-height: 340px; overflow:auto; }
    table { border-collapse: collapse; width:100%; font-size:.82rem; }
    th,td { text-align:left; padding:.3rem .4rem; border-bottom:1px solid var(--border); vertical-align:top; }
    th { font-size:.7rem; text-transform:uppercase; color: var(--dim); }
    .vig { font-variant-numeric: tabular-nums; padding:.05rem .4rem; border-radius:8px;
           background: color-mix(in oklab, var(--red) calc((1 - var(--v)) * 100%), var(--green)); color:#0b0f14; }
    .sev { display:inline-block; margin:.05rem; padding:.05rem .4rem; border-radius:8px; font-size:.72rem; }
    .sev-HIGH { background: var(--red); color:#fff; }
    .sev-MEDIUM { background: #a25c00; color:#fff; }
    .sev-LOW { background: #2c6; color:#0b0f14; }
    .disabled { pointer-events:none; opacity:.5; }
    .thumb { width:44px; height:33px; object-fit:cover; border-radius:4px; }
    .excludedRow td { opacity:.45; }
    .rescan { border:1px dashed var(--border); border-radius:8px; padding:.5rem .7rem; margin:.5rem 0; }
  `],
})
export class CropPanelComponent implements OnInit {
  @Input({ required: true }) mission!: Mission;
  @Input() center: [number, number] = [0, 0];
  private api = inject(ApiService);

  plants = signal<Plant[]>([]);
  reportUrl = signal<string>('');
  inspectAltM = 6;
  speedMps = 3;
  rescanArmed = signal(false);
  rescanPolygon = signal('');
  rescanAltM = 40;
  busy = signal(false);
  error = signal('');

  crops = computed(() => this.plants().filter(p => p.kind === 'CROP').length);
  trees = computed(() => this.plants().filter(p => p.kind === 'TREE').length);
  zones = computed(() => this.plants().filter(p => p.kind === 'CROP_FIELD').length);
  affected = computed(() => this.plants().filter(p => p.findings.length > 0).length);
  done = computed(() => this.mission.state === 'COMPLETE' && this.mission.hasReport);
  inProgress = computed(() => ['DETAIL_MISSION_UPLOADED', 'DETAIL_FLYING', 'PROCESSING']
    .includes(this.mission.state));
  canLaunch = computed(() => this.mission.state === 'PREVIEW_READY' && this.plants().length > 0);
  included = computed(() => this.plants().filter(p => !p.excluded).length);

  plantMarkers = computed(() => this.plants().map(p => ({
    lat: p.lat, lon: p.lon,
    label: p.kind === 'CROP_FIELD'
      ? `${p.label} field zone Ø${Math.round(p.canopyDiaM)} m${p.excluded ? ' (excluded)' : ''}`
      : `${p.label} ${p.kind.toLowerCase()}${p.excluded ? ' (excluded)' : ''}`,
    color: p.kind === 'TREE' ? '#3aa655' : p.kind === 'CROP_FIELD' ? '#3ba8a0' : '#ffb454',
    photoUrl: p.photoUrl ?? undefined,
    muted: p.excluded,
    radiusM: p.kind === 'CROP_FIELD' ? p.canopyDiaM / 2 : undefined,
  })));

  async ngOnInit() {
    await this.refresh();
  }

  async refresh() {
    this.plants.set(await firstValueFrom(this.api.plants(this.mission.id)));
    if (this.done()) {
      try { this.reportUrl.set((await firstValueFrom(this.api.reportUrl(this.mission.id))).reportUrl); }
      catch { /* report not ready */ }
    }
  }

  async toggleExclude(p: Plant) {
    this.busy.set(true);
    try {
      this.plants.set(await firstValueFrom(
        this.api.setPlantExcluded(this.mission.id, p.id, !p.excluded)));
    } finally { this.busy.set(false); }
  }

  async createFollowUp() {
    this.busy.set(true);
    try {
      const m = await firstValueFrom(this.api.createFollowUp(this.mission.id, {
        name: `${this.mission.name} — follow-up ${new Date().toISOString().slice(0, 10)}`,
      }));
      window.location.href = `/missions/${m.id}`;
    } finally { this.busy.set(false); }
  }

  async flyRescan() {
    this.busy.set(true); this.error.set('');
    try {
      await firstValueFrom(this.api.rescanDetection(this.mission.id, {
        polygonGeoJson: this.rescanPolygon(), altitudeM: this.rescanAltM, speedMps: 6,
      }));
      this.rescanArmed.set(false);
      this.rescanPolygon.set('');
    } catch (e: any) {
      this.error.set(e?.error?.message ?? 'Rescan launch failed.');
    } finally { this.busy.set(false); }
  }

  async launch() {
    this.busy.set(true); this.error.set('');
    try {
      await firstValueFrom(this.api.launchInspection(this.mission.id, {
        inspectAltM: this.inspectAltM, speedMps: this.speedMps,
      }));
    } catch (e: any) {
      this.error.set(e?.error?.message ?? 'Inspection launch failed.');
    } finally {
      this.busy.set(false);
    }
  }
}
