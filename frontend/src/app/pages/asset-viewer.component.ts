import { Component, ElementRef, OnDestroy, OnInit, ViewChild, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { AssetUrls } from '../core/models';
import { LasCloud, loadLas } from '../viewer/las-loader';
import { writeLas } from '../viewer/las-writer';
import { tryPotree } from '../viewer/potree-loader';
import { PointCloudViewerComponent } from '../viewer/point-cloud-viewer.component';
import { CloudReviewComponent } from '../viewer/cloud-review.component';

/**
 * Deliverable viewer. Strategy:
 *   1. try the Potree wrapper streaming COPC from the presigned URL,
 *   2. on any failure fall back to the built-in three.js viewer streaming
 *      the processed LAS — so pan/zoom/measure always works in the demo.
 */
@Component({
  standalone: true,
  imports: [RouterLink, DecimalPipe, PointCloudViewerComponent, CloudReviewComponent],
  template: `
    @if (urls(); as u) {
      <div class="head">
        <div>
          <h1>{{ u.asset.name }}</h1>
          <p class="mono dim">
            {{ u.asset.pointCount | number }} points · {{ u.asset.crs }}
            · bounds {{ u.asset.minX | number:'1.0-0' }},{{ u.asset.minY | number:'1.0-0' }}
            → {{ u.asset.maxX | number:'1.0-0' }},{{ u.asset.maxY | number:'1.0-0' }}
          </p>
        </div>
        <div class="actions">
          <span class="badge" [class.green]="u.asset.status === 'READY'" [class.red]="u.asset.status !== 'READY'">
            {{ u.asset.status === 'READY' ? 'PUBLISHED' : 'IN REVIEW' }}</span>
          <a class="btn" [href]="u.lazUrl" download>Download LAZ</a>
          <a class="btn" [routerLink]="['/missions', u.asset.missionId]">Mission</a>
        </div>
      </div>

      <p class="mono dim">{{ status() }}</p>

      @if (reviewMode() && cloud(); as c) {
        <!-- operator review: edit the cloud before the customer sees it -->
        <app-cloud-review [cloud]="c" (edited)="onEdited($event)" />
        <div class="card review-bar">
          <span class="mono dim">{{ reviewStatus() }}</span>
          <span class="spacer"></span>
          <button [disabled]="!dirty() || saving()" (click)="saveEdited()">Save edited cloud &amp; regenerate LAZ/COPC</button>
          <button class="btn-primary" [disabled]="saving()" (click)="publish()">Publish to customer</button>
        </div>
      } @else {
        <div #potreeHost [hidden]="mode() !== 'potree'" class="potree"></div>
        @if (mode() === 'builtin' && cloud(); as c) {
          <app-point-cloud-viewer [cloud]="c" />
        }
      }
    }
  `,
  styles: [`
    .head { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 1rem; }
    .actions { display: flex; gap: .6rem; align-items: center; }
    .potree { height: 560px; border: 1px solid var(--line); border-radius: 6px; }
    .review-bar { display: flex; gap: .6rem; align-items: center; margin-top: .6rem; }
    .spacer { flex: 1; }
  `],
})
export class AssetViewerComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  @ViewChild('potreeHost') potreeHost?: ElementRef<HTMLDivElement>;

  urls = signal<AssetUrls | null>(null);
  cloud = signal<LasCloud | null>(null);
  mode = signal<'loading' | 'potree' | 'builtin'>('loading');
  status = signal('Fetching asset…');
  reviewMode = signal(false);
  reviewStatus = signal('Edit the cloud, save, then publish to make it visible to the customer.');
  dirty = signal(false);
  saving = signal(false);

  private potreeViewer: any;

  async ngOnInit() {
    const u = await firstValueFrom(this.api.assetUrls(this.id));
    this.urls.set(u);

    // Operator + unpublished asset -> review/edit mode with the built-in editor.
    if (this.auth.isStaff && u.asset.status !== 'READY') {
      this.reviewMode.set(true);
      this.status.set('Review mode — streaming processed LAS…');
      try {
        this.cloud.set(await loadLas(u.lasUrl));
        this.status.set('Review mode · orbit: drag · pan: right-drag · zoom: scroll');
      } catch (e: any) {
        this.status.set('Could not load the point cloud: ' + (e?.message ?? e));
      }
      return;
    }

    // 1) Potree over COPC
    try {
      this.status.set('Loading Potree viewer (COPC stream)…');
      await new Promise(r => setTimeout(r));   // let the host div render
      this.potreeViewer = await tryPotree(this.potreeHost!.nativeElement, u.copcUrl);
      this.mode.set('potree');
      this.status.set('Potree · streaming COPC from object storage');
      return;
    } catch (e) {
      console.warn('Potree unavailable, using built-in viewer', e);
    }

    // 2) Built-in three.js viewer over the processed LAS
    this.status.set('Streaming processed LAS into the built-in viewer…');
    try {
      this.cloud.set(await loadLas(u.lasUrl));
      this.mode.set('builtin');
      this.status.set('Built-in viewer · orbit: drag · pan: right-drag · zoom: scroll');
    } catch (e: any) {
      this.status.set('Could not load the point cloud: ' + (e?.message ?? e));
    }
  }

  onEdited(_: LasCloud) { this.dirty.set(true); }

  /** Serialize the edited cloud to LAS, upload, and wait for the FINALIZE job to update the asset. */
  async saveEdited() {
    const c = this.cloud();
    if (!c) return;
    this.saving.set(true);
    this.reviewStatus.set('Uploading edited cloud…');
    try {
      await firstValueFrom(this.api.uploadEditedAsset(this.id, writeLas(c)));
      this.reviewStatus.set('Engine regenerating LAZ/COPC from the edited cloud…');
      const target = c.count;
      for (let i = 0; i < 40; i++) {           // poll up to ~2 min
        await new Promise(r => setTimeout(r, 3000));
        const a = await firstValueFrom(this.api.asset(this.id));
        if (a.pointCount === target) {
          this.reviewStatus.set(`Edited cloud saved (${target.toLocaleString()} points) — LAZ/COPC regenerated. Ready to publish.`);
          this.dirty.set(false);
          this.saving.set(false);
          return;
        }
      }
      this.reviewStatus.set('Still processing — check the mission jobs list.');
    } catch (e: any) {
      this.reviewStatus.set('Save failed: ' + (e?.error?.message ?? e?.message ?? e));
    } finally { this.saving.set(false); }
  }

  async publish() {
    if (this.dirty() && !confirm('You have unsaved edits — publish the last saved version anyway?')) return;
    this.saving.set(true);
    try {
      await firstValueFrom(this.api.publishAsset(this.id));
      this.reviewStatus.set('Published — the customer can now see this deliverable.');
      const u = this.urls();
      if (u) this.urls.set({ ...u, asset: { ...u.asset, status: 'READY' } });
      this.reviewMode.set(false);
      this.mode.set('builtin');                 // cloud already loaded: show the read-only viewer
    } finally { this.saving.set(false); }
  }

  ngOnDestroy(): void {
    try { this.potreeViewer?.renderer?.dispose?.(); } catch { /* best effort */ }
  }
}
