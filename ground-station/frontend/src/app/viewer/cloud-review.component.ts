import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild,
} from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { LasCloud } from './las-loader';

/**
 * Operator REVIEW editor for a delivered (merged) point cloud.
 *
 * The operator inspects the full cloud and cleans it up before the customer
 * sees it: draw a region (click vertices, plan view — Top view helps), then
 * Delete inside (remove artefacts/private areas) or Keep only inside (crop to
 * the area of interest). Undo-all restores the original. "Save" emits the
 * edited cloud so the page can serialize it to LAS and send it for
 * re-processing; "Publish" is handled by the host page.
 */
@Component({
  selector: 'app-cloud-review',
  standalone: true,
  template: `
    <div class="stage">
      <canvas #canvas></canvas>
      <div class="hud mono">
        <button (click)="setMode('nav')" [class.btn-primary]="mode === 'nav'">Navigate</button>
        <button (click)="setMode('region')" [class.btn-primary]="mode === 'region'">Draw region</button>
        <button (click)="topView()">Top view</button>
        <span class="sep"></span>
        <button (click)="deleteRegion(true)" [disabled]="vertices.length < 3">Delete inside</button>
        <button (click)="deleteRegion(false)" [disabled]="vertices.length < 3">Keep only inside</button>
        <button (click)="clearRegion()" [disabled]="vertices.length === 0">Clear region</button>
        <button (click)="undoAll()" [disabled]="!dirty">Undo all edits</button>
      </div>
      <div class="hint mono">{{ hint }} · {{ liveCount.toLocaleString() }} / {{ originalCount.toLocaleString() }} points{{ dirty ? ' · edited' : '' }}</div>
    </div>
  `,
  styles: [`
    .stage { position: relative; height: 560px; background: var(--bg-inset);
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
export class CloudReviewComponent implements AfterViewInit, OnDestroy {
  @Input({ required: true }) cloud!: LasCloud;
  /** Fires after every destructive edit with the current edited cloud. */
  @Output() edited = new EventEmitter<LasCloud>();

  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;

  mode: 'nav' | 'region' = 'nav';
  hint = 'Review the cloud. Draw region + Delete inside removes artefacts; Keep only inside crops to the zone of interest.';
  vertices: Array<[number, number]> = [];   // XY in LAS centered frame
  originalCount = 0;
  liveCount = 0;
  dirty = false;

  private renderer?: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera?: THREE.PerspectiveCamera;
  private controls?: OrbitControls;
  private points?: THREE.Points;
  private overlay = new THREE.Group();
  private markers: THREE.Mesh[] = [];
  private outline?: THREE.Line;
  private raycaster = new THREE.Raycaster();
  private frame = 0;
  private diag = 1;

  // pristine copies for undo-all
  private origPositions!: Float32Array;
  private origIntensities!: Float32Array;

  ngAfterViewInit(): void {
    this.origPositions = this.cloud.positions.slice();
    this.origIntensities = this.cloud.intensities.slice();
    this.originalCount = this.cloud.count;
    this.liveCount = this.cloud.count;

    const canvas = this.canvasRef.nativeElement;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.scene.background = new THREE.Color(0x10141a);
    this.diag = Math.hypot(...this.cloud.size);

    this.overlay.rotation.x = -Math.PI / 2;
    this.scene.add(this.overlay);
    this.rebuildPoints();

    this.camera = new THREE.PerspectiveCamera(55, 1, 0.1, this.diag * 20);
    this.camera.position.set(this.diag * 0.5, this.diag * 0.5, this.diag * 0.5);
    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.enableDamping = true;

    canvas.addEventListener('click', e => this.onClick(e));
    this.resize();
    window.addEventListener('resize', this.resize);
    this.animate();
  }

  // ------------------------------------------------------------ toolbar

  setMode(m: 'nav' | 'region'): void {
    this.mode = m;
    if (this.controls) this.controls.enableRotate = m === 'nav';
    this.hint = m === 'region'
      ? 'Click points to outline the region (Top view recommended).'
      : 'Navigate: drag to orbit · right-drag to pan · scroll to zoom.';
  }

  topView(): void {
    if (!this.camera || !this.controls) return;
    this.camera.position.set(0, this.diag * 1.1, 0.001);
    this.controls.target.set(0, 0, 0);
    this.controls.update();
  }

  clearRegion(): void {
    this.vertices = [];
    this.markers.forEach(m => this.overlay.remove(m));
    this.markers = [];
    if (this.outline) { this.overlay.remove(this.outline); this.outline = undefined; }
  }

  /** insideOut=true: delete points inside the region; false: keep only inside. */
  deleteRegion(insideOut: boolean): void {
    if (this.vertices.length < 3) return;
    const { positions, intensities } = this.cloud;
    const n = this.cloud.count;
    const keepPos: number[] = [];
    const keepInt: number[] = [];
    for (let i = 0; i < n; i++) {
      const inside = this.pointInRegion(positions[i * 3], positions[i * 3 + 1]);
      if (inside !== insideOut) {
        keepPos.push(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
        keepInt.push(intensities[i]);
      }
    }
    if (keepPos.length === 0) { this.hint = 'Edit would remove every point — not applied.'; return; }
    this.cloud.positions = new Float32Array(keepPos);
    this.cloud.intensities = new Float32Array(keepInt);
    this.cloud.count = keepInt.length;
    this.liveCount = keepInt.length;
    this.dirty = true;
    this.clearRegion();
    this.rebuildPoints();
    this.edited.emit(this.cloud);
    this.hint = insideOut ? 'Region deleted.' : 'Cropped to region.';
  }

  undoAll(): void {
    this.cloud.positions = this.origPositions.slice();
    this.cloud.intensities = this.origIntensities.slice();
    this.cloud.count = this.originalCount;
    this.liveCount = this.originalCount;
    this.dirty = false;
    this.clearRegion();
    this.rebuildPoints();
    this.edited.emit(this.cloud);
    this.hint = 'All edits reverted to the delivered cloud.';
  }

  // ------------------------------------------------------------ internals

  private pointInRegion(x: number, y: number): boolean {
    let inside = false;
    const v = this.vertices;
    for (let i = 0, j = v.length - 1; i < v.length; j = i++) {
      const [xi, yi] = v[i], [xj, yj] = v[j];
      if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }

  private onClick(e: MouseEvent): void {
    if (this.mode !== 'region' || !this.camera || !this.points) return;
    const rect = this.canvasRef.nativeElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(
      ((e.clientX - rect.left) / rect.width) * 2 - 1,
      -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    (this.raycaster.params as any).Points = { threshold: this.diag / 180 };
    const hit = this.raycaster.intersectObject(this.points)[0];
    if (!hit) return;
    const p = hit.point;
    this.vertices.push([p.x, -p.z]);

    const marker = new THREE.Mesh(
      new THREE.SphereGeometry(this.diag / 260),
      new THREE.MeshBasicMaterial({ color: 0xff6b6b }));
    marker.position.set(p.x, -p.z, p.y + 0.3);
    this.overlay.add(marker);
    this.markers.push(marker);

    if (this.outline) { this.overlay.remove(this.outline); this.outline = undefined; }
    if (this.vertices.length >= 2) {
      const zTop = this.cloud.maxZ - this.cloud.center[2] + 0.5;
      const pts = this.vertices.map(([x, y]) => new THREE.Vector3(x, y, zTop));
      this.outline = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints(
          this.vertices.length >= 3 ? [...pts, pts[0]] : pts),
        new THREE.LineBasicMaterial({ color: 0xff6b6b }));
      this.overlay.add(this.outline);
    }
  }

  private rebuildPoints(): void {
    if (this.points) {
      this.scene.remove(this.points);
      this.points.geometry.dispose();
      (this.points.material as THREE.Material).dispose();
    }
    const { positions, intensities, count, minZ, maxZ, center } = this.cloud;
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
    this.points = new THREE.Points(geo,
      new THREE.PointsMaterial({ size: this.diag / 500, vertexColors: true }));
    this.points.rotation.x = -Math.PI / 2;
    this.scene.add(this.points);
  }

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
