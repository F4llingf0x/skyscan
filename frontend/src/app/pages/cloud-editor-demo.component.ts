import { Component, OnInit, computed, signal } from '@angular/core';
import { CloudEditorComponent } from '../viewer/cloud-editor.component';
import { LasCloud, loadLas } from '../viewer/las-loader';
import { MapDrawComponent } from './map-draw.component';

/**
 * Standalone test bench for the crude point-cloud editor (no backend, no auth).
 *
 * Loads the bundled sample crude scan (assets/test-clouds/crude_fixed3d.las,
 * EPSG:32634) so the editor can be developed and demoed in isolation:
 *   http://localhost:4200/cloud-editor-demo
 * Draw a zone on the cloud → the WGS84 GeoJSON appears below and the polygon
 * shows on the reference map, proving the cloud↔map georeference round-trip.
 */
@Component({
  standalone: true,
  imports: [CloudEditorComponent, MapDrawComponent],
  template: `
    <h1>Cloud editor — test bench</h1>
    <p class="dim">Bundled test cloud: <span class="mono">crude_fixed3d.las</span> · {{ crs }} · no backend required.</p>

    @if (cloud(); as c) {
      <app-cloud-editor [cloud]="c" [crs]="crs" (polygon)="geojson.set($event)" />
    } @else {
      <p class="mono dim">{{ status() }}</p>
    }

    <div class="layout">
      <div class="card">
        <h2>Map reference</h2>
        <app-map-draw [center]="[47.4794, 19.1385]" [zoom]="15" [overlays]="overlays()" />
      </div>
      <div class="card">
        <h2>Emitted GeoJSON (WGS84)</h2>
        <pre class="mono out">{{ geojson() || '— draw a zone and press “Use this zone” —' }}</pre>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-top: 1rem; align-items: start; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } }
    .out { white-space: pre-wrap; word-break: break-all; font-size: .7rem; color: var(--text-dim);
      max-height: 380px; overflow-y: auto; }
  `],
})
export class CloudEditorDemoComponent implements OnInit {
  readonly crs = 'EPSG:32634';
  cloud = signal<LasCloud | null>(null);
  status = signal('Loading test cloud…');
  geojson = signal('');
  overlays = computed<Array<[string, string]>>(() =>
    this.geojson() ? [[this.geojson(), '#5cc8ff']] : []);

  async ngOnInit() {
    try {
      this.cloud.set(await loadLas('/assets/test-clouds/crude_fixed3d.las'));
    } catch (e: any) {
      this.status.set('Failed to load test cloud: ' + (e?.message ?? e));
    }
  }
}
