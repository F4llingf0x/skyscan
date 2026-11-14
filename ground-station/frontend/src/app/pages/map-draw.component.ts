import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnChanges,
  OnDestroy, Output, SimpleChanges, ViewChild,
} from '@angular/core';
import * as L from 'leaflet';
import 'leaflet-draw';

/**
 * Map panel used by the planner (draw crude polygon), the refine step
 * (crude polygon shown, draw detail polygon) and the live monitor
 * (polygons + moving vehicle marker with breadcrumb track).
 */
@Component({
  selector: 'app-map-draw',
  standalone: true,
  template: `<div #map class="map" role="application" aria-label="Mission map"></div>`,
  styles: [`.map { height: 440px; border: 1px solid var(--line); border-radius: 6px; }`],
})
export class MapDrawComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input() center: [number, number] = [47.4794, 19.1385];
  @Input() zoom = 16;
  @Input() drawEnabled = false;
  @Input() drawColor = '#ffb454';
  /** Existing polygons to display: [geojsonString, color][] */
  @Input() overlays: Array<[string, string]> = [];
  /** Live vehicle position; when set, a marker + breadcrumb are maintained. */
  @Input() vehicle: { lat: number; lon: number } | null = null;
  /** Static point markers (e.g. detected plants / animal tags). Markers are
   *  CLUSTERED at low zoom (count bubbles; click to zoom in) so dense fields
   *  don't render as an unreadable pile of overlapping tags. */
  @Input() markers: Array<{ lat: number; lon: number; label?: string; color?: string;
                            photoUrl?: string; muted?: boolean; radiusM?: number }> = [];
  @Output() polygon = new EventEmitter<string>();

  @ViewChild('map') mapEl!: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private drawnLayer?: L.FeatureGroup;
  private overlayGroup?: L.LayerGroup;
  private markerGroup?: L.LayerGroup;
  private marker?: L.CircleMarker;
  private track?: L.Polyline;

  ngAfterViewInit(): void {
    this.map = L.map(this.mapEl.nativeElement).setView(this.center, this.zoom);

    // Field/offline support: a local tile server (or cache proxy) on the site
    // LAN can be set once via localStorage['skyscan.tileUrl'], e.g.
    //   http://192.168.8.10:8081/tile/{z}/{x}/{y}.png
    // With no internet and no local server, tiles fail: after a few errors the
    // layer is removed and a neutral grid takes its place — polygon drawing,
    // routes, telemetry markers and coordinates all keep working tile-free.
    const tileUrl = localStorage.getItem('skyscan.tileUrl')
      ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
    const tiles = L.tileLayer(tileUrl, {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(this.map);
    let tileErrors = 0;
    tiles.on('tileerror', () => {
      if (++tileErrors < 4 || !this.map) return;
      this.map.removeLayer(tiles);
      const grid = (L.gridLayer as any)({ tileSize: 256 });
      grid.createTile = () => {
        const el = document.createElement('div');
        el.style.cssText =
          'width:256px;height:256px;box-sizing:border-box;' +
          'border:1px solid rgba(120,135,150,.18);background:#141a21;';
        return el;
      };
      grid.addTo(this.map);
      this.map.attributionControl?.setPrefix('offline — no basemap tiles');
    });

    this.overlayGroup = L.layerGroup().addTo(this.map);
    this.markerGroup = L.layerGroup().addTo(this.map);
    this.renderOverlays();
    this.renderMarkers();
    this.map.on('zoomend moveend', () => this.renderMarkers());

    if (this.drawEnabled) {
      this.drawnLayer = new L.FeatureGroup().addTo(this.map);
      const drawControl = new (L.Control as any).Draw({
        draw: {
          polygon: { shapeOptions: { color: this.drawColor, weight: 2 } },
          polyline: false, rectangle: false, circle: false, marker: false, circlemarker: false,
        },
        edit: { featureGroup: this.drawnLayer },
      });
      this.map.addControl(drawControl);

      this.map.on((L as any).Draw.Event.CREATED, (e: any) => {
        this.drawnLayer!.clearLayers();
        this.drawnLayer!.addLayer(e.layer);
        this.polygon.emit(JSON.stringify(e.layer.toGeoJSON().geometry));
      });
      this.map.on((L as any).Draw.Event.EDITED, (e: any) => {
        e.layers.eachLayer((layer: any) =>
          this.polygon.emit(JSON.stringify(layer.toGeoJSON().geometry)));
      });
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['overlays'] && this.map) this.renderOverlays();
    if (changes['markers'] && this.map) this.renderMarkers();
    if (changes['vehicle'] && this.map && this.vehicle) this.moveVehicle(this.vehicle);
  }

  private renderOverlays(): void {
    if (!this.overlayGroup) return;
    this.overlayGroup.clearLayers();
    const bounds: L.LatLngBounds[] = [];
    for (const [geojson, color] of this.overlays) {
      try {
        const layer = L.geoJSON(JSON.parse(geojson), { style: { color, weight: 2, fillOpacity: 0.08 } });
        layer.addTo(this.overlayGroup);
        bounds.push(layer.getBounds());
      } catch { /* ignore malformed geometry */ }
    }
    if (bounds.length && this.map) {
      const all = bounds.reduce((a, b) => a.extend(b));
      this.map.fitBounds(all.pad(0.3));
    }
  }

  private renderMarkers(): void {
    if (!this.markerGroup || !this.map) return;
    this.markerGroup.clearLayers();
    if (!this.markers.length) return;

    // Grid clustering in SCREEN space: markers within ~36 px share a cell.
    const cell = 36;
    const groups = new Map<string, typeof this.markers>();
    for (const mk of this.markers.filter(m => !m.radiusM)) {
      const p = this.map.latLngToContainerPoint([mk.lat, mk.lon]);
      const key = `${Math.round(p.x / cell)}:${Math.round(p.y / cell)}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(mk);
    }

    const pts: L.LatLngExpression[] = this.markers.map(mk => [mk.lat, mk.lon]);
    // ZONES (radiusM set, e.g. crop fields) render as translucent circles in
    // real meters — they never cluster, they ARE the area.
    for (const mk of this.markers.filter(m => m.radiusM)) {
      const c = mk.color ?? '#3ba8a0';
      const z = L.circle([mk.lat, mk.lon], {
        radius: mk.radiusM!, color: c, weight: 2,
        fillColor: c, fillOpacity: mk.muted ? 0.06 : 0.15, opacity: mk.muted ? 0.3 : 0.8,
      }).bindTooltip(mk.label ?? '', { direction: 'center' });
      if (mk.photoUrl || mk.label) {
        z.bindPopup(`<div style="text-align:center;max-width:220px">`
          + (mk.photoUrl ? `<img src="${mk.photoUrl}" style="width:200px;border-radius:6px" alt="">` : '')
          + `<div style="font:12px monospace;margin-top:4px">${mk.label ?? ''}</div></div>`);
      }
      z.addTo(this.markerGroup);
    }
    for (const group of groups.values()) {
      if (group.length === 1) {
        const mk = group[0];
        const c = mk.color ?? '#ffb454';
        const m = L.circleMarker([mk.lat, mk.lon], {
          radius: 5, color: c, fillColor: c,
          fillOpacity: mk.muted ? 0.25 : 0.9, opacity: mk.muted ? 0.35 : 1, weight: 1,
        }).bindTooltip(mk.label ?? '', { direction: 'top', offset: [0, -4] });
        if (mk.photoUrl || mk.label) {
          m.bindPopup(`<div style="text-align:center;max-width:220px">`
            + (mk.photoUrl ? `<img src="${mk.photoUrl}" style="width:200px;border-radius:6px" alt="">` : '')
            + `<div style="font:12px monospace;margin-top:4px">${mk.label ?? ''}</div></div>`);
        }
        m.addTo(this.markerGroup);
      } else {
        // Cluster bubble: count + dominant color; click zooms into the group.
        const cLat = group.reduce((s, m) => s + m.lat, 0) / group.length;
        const cLon = group.reduce((s, m) => s + m.lon, 0) / group.length;
        const color = group[0].color ?? '#ffb454';
        const icon = L.divIcon({
          className: 'mk-cluster',
          html: `<div style="background:${color};color:#0b0f14;border-radius:50%;`
            + `width:26px;height:26px;display:flex;align-items:center;justify-content:center;`
            + `font:600 12px sans-serif;border:2px solid rgba(255,255,255,.8)">${group.length}</div>`,
          iconSize: [26, 26], iconAnchor: [13, 13],
        });
        L.marker([cLat, cLon], { icon })
          .bindTooltip(`${group.length} items — click to zoom`, { direction: 'top' })
          .on('click', () => {
            const b = L.latLngBounds(group.map(m => [m.lat, m.lon] as L.LatLngExpression));
            this.map!.fitBounds(b.pad(0.5), { maxZoom: 20 });
          })
          .addTo(this.markerGroup);
      }
    }
    if (this.overlays.length === 0 && !this._markersFitted) {
      this._markersFitted = true;
      this.map.fitBounds(L.latLngBounds(pts).pad(0.3));
    }
  }
  private _markersFitted = false;

  private moveVehicle(v: { lat: number; lon: number }): void {
    const pos: L.LatLngExpression = [v.lat, v.lon];
    if (!this.marker) {
      this.marker = L.circleMarker(pos, { radius: 7, color: '#ffb454', fillColor: '#ffb454', fillOpacity: 0.9 })
        .addTo(this.map!).bindTooltip('vehicle');
      this.track = L.polyline([pos], { color: '#ffb454', weight: 1.5, dashArray: '4 4' }).addTo(this.map!);
    } else {
      this.marker.setLatLng(pos);
      this.track!.addLatLng(pos);
    }
  }

  ngOnDestroy(): void { this.map?.remove(); }
}
