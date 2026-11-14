import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild,
} from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { LasCloud } from './las-loader';

/**
 * Built-in point-cloud viewer (three.js): orbit / pan / zoom plus a two-click
 * distance measurement tool. Renders the processed LAS streamed from object
 * storage; colors by elevation, sized by intensity.
 */
@Component({
  selector: 'app-point-cloud-viewer',
  standalone: true,
  template: `
    <div class="stage">
      <canvas #canvas></canvas>
      <div class="hud mono">
        <button (click)="toggleMeasure()" [class.btn-primary]="measuring">
          {{ measuring ? 'Measuring — click two points' : 'Measure distance' }}
        </button>
        @if (measureText) { <span class="readout">{{ measureText }}</span> }
      </div>
    </div>
  `,
  styles: [`
    .stage { position: relative; height: 560px; background: var(--bg-inset);
      border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
    canvas { width: 100%; height: 100%; display: block; }
    .hud { position: absolute; top: .6rem; left: .6rem; display: flex; gap: .6rem; align-items: center; }
    .readout { background: var(--bg-raised); border: 1px solid var(--amber);
      color: var(--amber); padding: .3rem .6rem; border-radius: 4px; font-size: .8rem; }
  `],
})
export class PointCloudViewerComponent implements AfterViewInit, OnDestroy {
  @Input({ required: true }) cloud!: LasCloud;
  @Output() ready = new EventEmitter<void>();
  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;

  measuring = false;
  measureText = '';

  private renderer?: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera?: THREE.PerspectiveCamera;
  private controls?: OrbitControls;
  private points?: THREE.Points;
  private raycaster = new THREE.Raycaster();
  private measurePts: THREE.Vector3[] = [];
  private markers: THREE.Mesh[] = [];
  private line?: THREE.Line;
  private frame = 0;

  ngAfterViewInit(): void {
    const canvas = this.canvasRef.nativeElement;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(window.devicePixelRatio);

    const { positions, intensities, count, size, minZ, maxZ } = this.cloud;

    // Color ramp by elevation (terrain-ish), brightness modulated by intensity.
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
    const mat = new THREE.PointsMaterial({ size: Math.max(...size) / 500, vertexColors: true });
    this.points = new THREE.Points(geo, mat);
    this.points.rotation.x = -Math.PI / 2;   // LAS z-up -> three.js y-up
    this.scene.add(this.points);
    this.scene.background = new THREE.Color(0x10141a);

    const diag = Math.hypot(...size);
    this.camera = new THREE.PerspectiveCamera(55, 1, 0.1, diag * 20);
    this.camera.position.set(diag * 0.5, diag * 0.5, diag * 0.5);
    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.enableDamping = true;

    canvas.addEventListener('click', e => this.onClick(e));
    this.resize();
    window.addEventListener('resize', this.resize);
    this.animate();
    this.ready.emit();
  }

  toggleMeasure(): void {
    this.measuring = !this.measuring;
    if (!this.measuring) this.clearMeasurement();
  }

  private onClick(e: MouseEvent): void {
    if (!this.measuring || !this.camera || !this.points) return;
    const rect = this.canvasRef.nativeElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(
      ((e.clientX - rect.left) / rect.width) * 2 - 1,
      -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    (this.raycaster.params as any).Points = { threshold: Math.max(...this.cloud.size) / 200 };
    const hit = this.raycaster.intersectObject(this.points)[0];
    if (!hit) return;

    if (this.measurePts.length === 2) this.clearMeasurement();
    this.measurePts.push(hit.point.clone());
    this.addMarker(hit.point);

    if (this.measurePts.length === 2) {
      const [a, b] = this.measurePts;
      const d = a.distanceTo(b);
      this.measureText = `${d.toFixed(2)} m`;
      const lineGeo = new THREE.BufferGeometry().setFromPoints([a, b]);
      this.line = new THREE.Line(lineGeo, new THREE.LineBasicMaterial({ color: 0xffb454 }));
      this.scene.add(this.line);
    }
  }

  private addMarker(p: THREE.Vector3): void {
    const m = new THREE.Mesh(
      new THREE.SphereGeometry(Math.max(...this.cloud.size) / 300),
      new THREE.MeshBasicMaterial({ color: 0xffb454 }));
    m.position.copy(p);
    this.scene.add(m);
    this.markers.push(m);
  }

  private clearMeasurement(): void {
    this.measurePts = [];
    this.measureText = '';
    this.markers.forEach(m => this.scene.remove(m));
    this.markers = [];
    if (this.line) { this.scene.remove(this.line); this.line = undefined; }
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
