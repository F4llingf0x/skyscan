import { Component, Input, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { DecimalPipe, DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { MapDrawComponent } from './map-draw.component';
import { Mission, Sighting } from '../core/models';

/**
 * WILDLIFE_SURVEY side panel. Single-pass mission, so the panel has two jobs:
 *  - during the flight: live sighting feed (SSE "animal" events append rows
 *    the moment the drone tags them);
 *  - after COMPLETE: the full tag list (species, time of finding, location,
 *    source, body temp) on the map + table, and the report link.
 */
@Component({
  selector: 'app-wildlife-panel',
  standalone: true,
  imports: [DecimalPipe, DatePipe, MapDrawComponent],
  template: `
    <div class="card">
      <h2>Wildlife survey
        <span class="badge" [class.amber]="!done()" [class.green]="done()">
          {{ done() ? 'report ready' : (flying() ? 'live' : 'RGB + thermal') }}</span></h2>

      @if (sightings().length) {
        <app-map-draw [center]="center" [drawEnabled]="false" [markers]="markers()" />
        <div class="tally mono">
          {{ sightings().length }} animals tagged · {{ speciesCount() }} species
          · {{ fusedCount() }} thermal-confirmed
          @if (mission.cameraModule) { · cam {{ mission.cameraModule }} }
        </div>
      } @else {
        <p class="dim">{{ flying()
          ? 'Watching for animals — tags appear here the moment the drone finds one…'
          : 'No sightings yet.' }}</p>
      }

      @if (done()) {
        <a class="btn-primary" [href]="reportUrl()" target="_blank" rel="noopener"
           [class.disabled]="!reportUrl()">Open wildlife survey report</a>
      }
    </div>

    @if (sightings().length) {
      <div class="card">
        <h2>Sighting tags <span class="badge amber">time-ordered</span></h2>
        <div class="rows">
          <table>
            <tr><th>Tag</th><th>Photo</th><th>Species</th><th>Time of finding</th><th>Source</th><th>°C</th><th>Conf</th></tr>
            @for (s of sightings(); track s.label) {
              <tr>
                <td class="mono">{{ s.label }}</td>
                <td>
                  @if (s.photoUrl) {
                    <a [href]="s.photoUrl" target="_blank" rel="noopener">
                      <img [src]="s.photoUrl" class="thumb" alt="{{ s.label }}"></a>
                  } @else { <span class="dim">—</span> }
                </td>
                <td>{{ s.species }}</td>
                <td class="mono dim">{{ s.sightedAt | date:'HH:mm:ss' }}</td>
                <td><span class="src src-{{ s.source }}">{{ s.source }}</span></td>
                <td class="mono">{{ s.bodyTempC != null ? (s.bodyTempC | number:'1.1-1') : '—' }}</td>
                <td class="mono">{{ s.confidence | number:'1.2-2' }}</td>
              </tr>
            }
          </table>
        </div>
      </div>
    }
  `,
  styles: [`
    .tally { margin:.5rem 0; }
    .rows { max-height: 320px; overflow:auto; }
    table { border-collapse: collapse; width:100%; font-size:.82rem; }
    th,td { text-align:left; padding:.3rem .4rem; border-bottom:1px solid var(--border); }
    th { font-size:.7rem; text-transform:uppercase; color: var(--dim); }
    .src { padding:.05rem .4rem; border-radius:8px; font-size:.72rem; }
    .src-FUSED { background:#0a7d5c; color:#fff; }
    .src-THERMAL { background:#a25c00; color:#fff; }
    .src-RGB { background:#185fa5; color:#fff; }
    .disabled { pointer-events:none; opacity:.5; }
    .thumb { width:44px; height:33px; object-fit:cover; border-radius:4px; }
  `],
})
export class WildlifePanelComponent implements OnInit, OnDestroy {
  @Input({ required: true }) mission!: Mission;
  @Input() center: [number, number] = [0, 0];
  private api = inject(ApiService);
  private es?: EventSource;

  sightings = signal<Sighting[]>([]);
  reportUrl = signal('');

  speciesCount = computed(() => new Set(this.sightings().map(s => s.species)).size);
  fusedCount = computed(() => this.sightings().filter(s => s.source === 'FUSED').length);
  done = computed(() => this.mission.state === 'COMPLETE' && this.mission.hasReport);
  flying = computed(() => ['CRUDE_MISSION_UPLOADED', 'CRUDE_FLYING'].includes(this.mission.state));

  markers = computed(() => this.sightings().map(s => ({
    lat: s.lat, lon: s.lon,
    label: `${s.label} ${s.species} (${new Date(s.sightedAt).toLocaleTimeString()})`,
    color: s.source === 'FUSED' ? '#0a7d5c' : s.source === 'THERMAL' ? '#a25c00' : '#185fa5',
    photoUrl: s.photoUrl ?? undefined,
  })));

  async ngOnInit() {
    await this.refresh();
    // Live feed: the drone streams each new tag during the pass.
    this.es = this.api.missionEvents(this.mission.id);
    this.es.addEventListener('animal', (e: MessageEvent) => {
      try {
        const s = JSON.parse(e.data);
        if (s?.label && !this.sightings().some(x => x.label === s.label)) {
          this.sightings.set([...this.sightings(), s]);
        }
      } catch { /* malformed live frame — the landing upload has the truth */ }
    });
    this.es.addEventListener('state', () => this.refresh());
  }

  ngOnDestroy() { this.es?.close(); }

  async refresh() {
    try { this.sightings.set(await firstValueFrom(this.api.sightings(this.mission.id))); }
    catch { /* none yet */ }
    if (this.done() && !this.reportUrl()) {
      try { this.reportUrl.set((await firstValueFrom(this.api.reportUrl(this.mission.id))).reportUrl); }
      catch { /* not ready */ }
    }
  }
}
