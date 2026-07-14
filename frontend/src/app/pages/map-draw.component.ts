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
  @Output() polygon = new EventEmitter<string>();

  @ViewChild('map') mapEl!: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private drawnLayer?: L.FeatureGroup;
  private overlayGroup?: L.LayerGroup;
  private marker?: L.CircleMarker;
  private track?: L.Polyline;

  ngAfterViewInit(): void {
    this.map = L.map(this.mapEl.nativeElement).setView(this.center, this.zoom);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(this.map);

    this.overlayGroup = L.layerGroup().addTo(this.map);
    this.renderOverlays();

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
