import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { Site } from '../core/models';

/**
 * Highly stylized, tile-free world map for the dashboard: just country borders
 * and capital cities, drawn from Natural Earth GeoJSON in the console palette —
 * no basemap imagery. Projects are pinned in amber with their names; zoom and
 * pan work as usual; clicking a pin opens the site.
 *
 * Data: /assets/world/*.geojson (baked into the image at build time), with a
 * CDN fallback so `npm start` works before the assets exist locally.
 */
@Component({
  selector: 'app-sites-map',
  standalone: true,
  template: `
    <div #map class="worldmap" role="application" aria-label="Projects world map"></div>
    @if (loadError) { <p class="mono dim">{{ loadError }}</p> }
  `,
  styles: [`
    .worldmap { height: 68vh; min-height: 420px; border: 1px solid var(--line);
      border-radius: 6px; background: var(--bg-inset); }
    :host ::ng-deep .capital-tip {
      background: transparent; border: none; box-shadow: none;
      color: var(--text-dim); font-family: var(--font-mono); font-size: .68rem;
    }
    :host ::ng-deep .site-tip {
      background: var(--bg-raised); border: 1px solid var(--amber); box-shadow: none;
      color: var(--amber); font-family: var(--font-mono); font-size: .72rem;
      padding: .15rem .45rem; border-radius: 3px;
    }
    :host ::ng-deep .site-tip::before { display: none; }
    :host ::ng-deep .leaflet-container { background: var(--bg-inset); }
  `],
})
export class SitesMapComponent implements AfterViewInit, OnDestroy {
  @Input() sites: Site[] = [];
  @Output() open = new EventEmitter<Site>();
  @ViewChild('map') mapEl!: ElementRef<HTMLDivElement>;

  loadError = '';
  private map?: L.Map;

  async ngAfterViewInit(): Promise<void> {
    this.map = L.map(this.mapEl.nativeElement, {
      minZoom: 2, maxZoom: 9, worldCopyJump: false, attributionControl: false,
      maxBounds: [[-85, -180], [85, 180]], maxBoundsViscosity: 0.8, zoomControl: true,
    });
    // No tile layer on purpose — the "basemap" is our own stylized vector layer.
    this.centerOnSites();

    try {
      const [countries, places] = await Promise.all([
        this.fetchGeo('/assets/world/countries.geo.json',
          'https://raw.githubusercontent.com/johan/world.geo.json/master/countries.geo.json'),
        this.fetchGeo('/assets/world/capitals.geojson',
          'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_populated_places_simple.geojson'),
      ]);

      // country borders — hairline strokes, faint fill
      L.geoJSON(countries, {
        style: { color: '#2c3644', weight: 1, fillColor: '#1d242e', fillOpacity: 0.55 },
      }).addTo(this.map);

      // capitals — subtle dots, name on hover
      const capitals = (places.features as any[]).filter(f =>
        String(f.properties?.featurecla ?? '').startsWith('Admin-0 capital'));
      for (const f of capitals) {
        const [lon, lat] = f.geometry.coordinates;
        L.circleMarker([lat, lon], {
          radius: 2, color: '#8b97a5', fillColor: '#8b97a5', fillOpacity: 0.9, weight: 0,
        }).bindTooltip(f.properties.name, { className: 'capital-tip', direction: 'top', offset: [0, -4] })
          .addTo(this.map!);
      }
    } catch (e: any) {
      this.loadError = 'World layer unavailable (' + (e?.message ?? e) + ') — pins still shown.';
    }

    // project pins — amber, name always visible, click to open
    for (const s of this.sites) {
      const pin = L.circleMarker([s.centerLat, s.centerLon], {
        radius: 7, color: '#ffb454', fillColor: '#ffb454', fillOpacity: 0.95, weight: 2,
      }).bindTooltip(s.name, { permanent: true, className: 'site-tip', direction: 'top', offset: [0, -8] })
        .addTo(this.map);
      pin.on('click', () => this.open.emit(s));
    }
  }

  private centerOnSites(): void {
    if (!this.map) return;
    if (this.sites.length === 0) { this.map.setView([30, 10], 2); return; }
    const bounds = L.latLngBounds(this.sites.map(s => [s.centerLat, s.centerLon] as [number, number]));
    if (this.sites.length === 1) this.map.setView(bounds.getCenter(), 4);
    else this.map.fitBounds(bounds.pad(0.5), { maxZoom: 5 });
  }

  private async fetchGeo(local: string, cdn: string): Promise<any> {
    for (const url of [local, cdn]) {
      try {
        const res = await fetch(url);
        if (res.ok) return await res.json();
      } catch { /* try next source */ }
    }
    throw new Error('no data source reachable');
  }

  ngOnDestroy(): void { this.map?.remove(); }
}
