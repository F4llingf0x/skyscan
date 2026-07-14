import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output,
  SimpleChanges, ViewChild,
} from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { LasCloud } from './las-loader';
import { geoJsonToUtmRing, utmRingToGeoJson } from '../core/geo';

/**
 * Crude point-cloud EDITOR (step 2 of the refine flow).
 *
 * The operator inspects the crude scan in 3D and draws the detail-zone polygon
 * directly on the points: click vertices in Draw mode, close the ring, and the
 * component emits the polygon as a WGS84 GeoJSON string — the exact payload the
 * existing /refine endpoint and the Leaflet map already understand, so the 3D
 * selection and the 2D map stay referenced to the same ground (via the cloud's
 * projected CRS, e.g. EPSG:32634).
 *
 * Modes: Navigate (orbit/pan/zoom) · Draw zone (click to add vertices).
 * Tools:  Top view · Undo vertex · Clear · Use this zone (emits).
 */
@Component({
  selector: 'app-cloud-editor',
  standalone: true,
  template: `
    <div class="stage">
      <canvas #canvas></canvas>
      <div class="hud mono">
        <button (click)="setMode('nav')" [class.btn-primary]="mode === 'nav'">Navigate</button>
        <button (click)="setMode('draw')" [class.btn-primary]="mode === 'draw'">Draw zone</button>
        <button (click)="topView()">Top view</button>
        <span class="sep"></span>
        <button (click)="undoVertex()" [disabled]="vertices.length === 0">Undo vertex</button>
        <button (click)="clearZone()" [disabled]="vertices.length === 0">Clear</button>
        <button class="btn-primary" (click)="emitZone()" [disabled]="vertices.length < 3">
          Use this zone ({{ vertices.length }} pts)
        </button>
      </div>
      <div class="hint mono">{{ hint }}</div>
    </div>
  `,
  styles: [`
    .stage { position: relative; height: 520px; background: var(--bg-inset);
      border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
    canvas { width: 100%; height: 100%; display: block; }
    .hud { position: absolute; top: .6rem; left: .6rem; display: flex; gap: .5rem;
      align-items: center; flex-wrap: wrap; }
    .hud button { font-size: .72rem; padding: .35rem .7rem; }
    .sep { width: 1px; height: 20px; background: var(--line); }
    .hint { position: absolute; bottom: .6rem; left: .6rem; font-size: .7rem;
      color: var(--text-dim); background: rgba(16,20,26,.75); padding: .25rem .6rem;
      border-radius: 4px; }
  `],
})
export class CloudEditorComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) cloud!: LasCloud;
  /** Projected CRS of the cloud, e.g. "EPSG:32634" — needed for the map reference. */
  @Input({ required: true }) crs!: string;
  /** Two-way sync: zone set elsewhere (e.g. drawn on the 2D map) rendered on the cloud. */
  @Input() zoneGeoJson = '';
  /** Planned coverage route (WGS84 GeoJSON LineString) rendered above the cloud. */
  @Input() routeGeoJson = '';
  /** Flight altitude for the 3D route ribbon (m above the cloud's mean ground). */
  @Input() routeAltitudeM = 35;
  /** Emits the detail zone as WGS84 GeoJSON Polygon string. */
  @Output() polygon = new EventEmitter<string>();

  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;

  mode: 'nav' | 'draw' = 'nav';
  hint = 'Navigate: drag to orbit · right-drag to pan · scroll to zoom. Switch to Draw zone to select the detail area on the cloud.';
  vertices: Array<[number, number, number]> = [];   // LAS-frame coords (centered)

  private renderer?: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera?: THREE.PerspectiveCamera;
  private controls?: OrbitControls;
  private points?: THREE.Points;
  /** Group sharing the points' rotation so children use LAS (z-up) coordinates. */
  private overlay = new THREE.Group();
  private markers: THREE.Mesh[] = [];
  private outline?: THREE.Line;
  private fill?: THREE.Mesh;
  private externalZone?: THREE.Group;
  private routeLine?: THREE.Line;
  private raycaster = new THREE.Raycaster();
  private frame = 0;
  private diag = 1;

  ngAfterViewInit(): void {
    const canvas = this.canvasRef.nativeElement;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.scene.background = new THREE.Color(0x10141a);

    const { positions, intensities, count, size, minZ, maxZ } = this.cloud;

    // elevation ramp × intensity, same language as the deliverable viewer
    const colors = new Float32Array(count * 3);
    const zRange = Math.max(0.01, maxZ - minZ);
    const czOffset = (minZ + maxZ) / 2 - minZ;
    const ramp = (t: number): [number, number, number] => {
      if (t < 0.33) { const u = t / 0.33; return [0.15 + 0.1 * u, 0.35 + 0.35 * u, 0.55 - 0.25 * u]; }
      if (t < 0.66) { const u = (t - 0.33) / 0.33; return [0.25 + 0.55 * u, 0.7, 0.3 - 0.1 * u]; }
      const u = (t - 0.66) / 0.34; return [0.8 + 0.2 * u, 0.7 - 0.35 * u, 0.2 + 0.6 * u];
    };
    for (let i = 0; i < count; i++) {
      const z = positions[i * 3 + 2] + czOffset;
      const t = Math.min(1, Math.max(0, z / zRange));
      const [r, g, b] = ramp(t);
      const k = 0.55 + 0.45 * intensities[i];
      colors[i * 3] = r * k; colors[i * 3 + 1] = g * k; colors[i * 3 + 2] = b * k;
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    this.diag = Math.hypot(...size);
    const mat = new THREE.PointsMaterial({ size: this.diag / 450, vertexColors: true });
    this.points = new THREE.Points(geo, mat);
    this.points.rotation.x = -Math.PI / 2;    // LAS z-up -> three.js y-up
    this.overlay.rotation.x = -Math.PI / 2;   // overlay children live in LAS coordinates
    this.scene.add(this.points, this.overlay);

    this.camera = new THREE.PerspectiveCamera(55, 1, 0.1, this.diag * 20);
    this.camera.position.set(this.diag * 0.5, this.diag * 0.5, this.diag * 0.5);
    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.enableDamping = true;

    canvas.addEventListener('click', e => this.onClick(e));
    this.resize();
    window.addEventListener('resize', this.resize);
    this.animate();
    this.renderExternalZone();
    this.renderRoute();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.renderer) return;   // before view init: ngAfterViewInit renders both
    if (changes['zoneGeoJson']) this.renderExternalZone();
    if (changes['routeGeoJson'] || changes['routeAltitudeM']) this.renderRoute();
  }

  /** Zone coming from outside (map drawing or a previous session): shown in cyan on the cloud. */
  private renderExternalZone(): void {
    if (this.externalZone) { this.overlay.remove(this.externalZone); this.externalZone = undefined; }
    if (!this.zoneGeoJson) return;
    try {
      const [cx, cy] = this.cloud.center;
      const ring = geoJsonToUtmRing(this.crs, this.zoneGeoJson)
        .map(([e, n]) => [e - cx, n - cy] as [number, number]);
      const zTop = this.groundZ() + 0.6;
      const group = new THREE.Group();
      const pts = ring.map(([x, y]) => new THREE.Vector3(x, y, zTop));
      group.add(new THREE.Line(
        new THREE.BufferGeometry().setFromPoints([...pts, pts[0]]),
        new THREE.LineBasicMaterial({ color: 0x5cc8ff })));
      const shape = new THREE.Shape(ring.map(([x, y]) => new THREE.Vector2(x, y)));
      const fill = new THREE.Mesh(new THREE.ShapeGeometry(shape), new THREE.MeshBasicMaterial({
        color: 0x5cc8ff, transparent: true, opacity: 0.12, side: THREE.DoubleSide, depthWrite: false }));
      fill.position.z = zTop;
      group.add(fill);
      this.overlay.add(group);
      this.externalZone = group;
    } catch { /* zone in a different CRS or malformed — skip rendering */ }
  }

  /** Planned coverage route rendered as an amber ribbon at flight altitude. */
  private renderRoute(): void {
    if (this.routeLine) { this.overlay.remove(this.routeLine); this.routeLine = undefined; }
    if (!this.routeGeoJson) return;
    try {
      const [cx, cy] = this.cloud.center;
      const z = this.groundZ() + this.routeAltitudeM;
      const pts = geoJsonToUtmRing(this.crs, this.routeGeoJson)
        .map(([e, n]) => new THREE.Vector3(e - cx, n - cy, z));
      this.routeLine = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints(pts),
        new THREE.LineBasicMaterial({ color: 0xffb454 }));
      this.overlay.add(this.routeLine);
    } catch { /* skip */ }
  }

  /** Mean ground elevation in the cloud's centered frame. */
  private groundZ(): number {
    const { minZ, maxZ, center } = this.cloud;
    return (minZ + maxZ) / 2 - center[2];
  }

  // ------------------------------------------------------------ toolbar

  setMode(m: 'nav' | 'draw'): void {
    this.mode = m;
    if (this.controls) this.controls.enableRotate = m === 'nav';   // pan/zoom stay available while drawing
    this.hint = m === 'draw'
      ? 'Draw: click points on the cloud to add vertices (Top view helps). Close with “Use this zone”.'
      : 'Navigate: drag to orbit · right-drag to pan · scroll to zoom.';
  }

  topView(): void {
    if (!this.camera || !this.controls) return;
    this.camera.position.set(0, this.diag * 1.1, 0.001);
    this.controls.target.set(0, 0, 0);
    this.controls.update();
  }

  undoVertex(): void {
    this.vertices.pop();
    const m = this.markers.pop();
    if (m) this.overlay.remove(m);
    this.redrawZone();
  }

  clearZone(): void {
    this.vertices = [];
    this.markers.forEach(m => this.overlay.remove(m));
    this.markers = [];
    this.redrawZone();
  }

  emitZone(): void {
    if (this.vertices.length < 3) return;
    const [cx, cy] = this.cloud.center;
    const ring: Array<[number, number]> = this.vertices.map(([x, y]) => [x + cx, y + cy]);
    const geojson = utmRingToGeoJson(this.crs, ring);
    this.polygon.emit(geojson);
    this.hint = `Detail zone captured (${this.vertices.length} vertices) — synced to the map, ready for route planning.`;
    this.clearZone();   // the emitted zone comes back via [zoneGeoJson] as the single source of truth
  }

  // ------------------------------------------------------------ drawing

  private onClick(e: MouseEvent): void {
    if (this.mode !== 'draw' || !this.camera || !this.points) return;
    const rect = this.canvasRef.nativeElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(
      ((e.clientX - rect.left) / rect.width) * 2 - 1,
      -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    (this.raycaster.params as any).Points = { threshold: this.diag / 180 };
    const hit = this.raycaster.intersectObject(this.points)[0];
    if (!hit) return;

    // world (y-up) -> LAS frame (z-up): rotation -90° about X inverse
    const p = hit.point;
    const las: [number, number, number] = [p.x, -p.z, p.y];
    this.vertices.push(las);

    const marker = new THREE.Mesh(
      new THREE.SphereGeometry(this.diag / 260),
      new THREE.MeshBasicMaterial({ color: 0x5cc8ff }));
    marker.position.set(las[0], las[1], las[2] + 0.2);
    this.overlay.add(marker);
    this.markers.push(marker);
    this.redrawZone();
  }

  private redrawZone(): void {
    if (this.outline) { this.overlay.remove(this.outline); this.outline = undefined; }
    if (this.fill) { this.overlay.remove(this.fill); this.fill = undefined; }
    if (this.vertices.length < 2) return;

    const zTop = Math.max(...this.vertices.map(v => v[2])) + 0.4;
    const pts = this.vertices.map(([x, y]) => new THREE.Vector3(x, y, zTop));
    const outlineGeo = new THREE.BufferGeometry().setFromPoints(
      this.vertices.length >= 3 ? [...pts, pts[0]] : pts);
    this.outline = new THREE.Line(outlineGeo, new THREE.LineBasicMaterial({ color: 0x5cc8ff }));
    this.overlay.add(this.outline);

    if (this.vertices.length >= 3) {
      const shape = new THREE.Shape(this.vertices.map(([x, y]) => new THREE.Vector2(x, y)));
      const fillGeo = new THREE.ShapeGeometry(shape);
      this.fill = new THREE.Mesh(fillGeo, new THREE.MeshBasicMaterial({
        color: 0x5cc8ff, transparent: true, opacity: 0.14, side: THREE.DoubleSide, depthWrite: false,
      }));
      this.fill.position.z = zTop;
      this.overlay.add(this.fill);
    }
  }

  // ------------------------------------------------------------ plumbing

  private resize = (): void => {
    if (!this.renderer || !this.camera) return;
    const el = this.canvasRef.nativeElement.parentElement!;
    this.renderer.setSize(el.clientWidth, el.clientHeight, false);
    this.camera.aspect = el.clientWidth / el.clientHeight;
    this.camera.updateProjectionMatrix();
  };

  private animate = (): void => {
    this.frame = requestAnimationFrame(this.animate);
    this.controls?.update();
    if (this.renderer && this.camera) this.renderer.render(this.scene, this.camera);
  };

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
    window.removeEventListener('resize', this.resize);
    this.renderer?.dispose();
  }
}
