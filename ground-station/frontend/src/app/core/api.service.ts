import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Asset, AssetUrls, Mission, Plant, Site } from './models';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);

  sites(): Observable<Site[]> { return this.http.get<Site[]>('/api/sites'); }
  site(id: string): Observable<Site> { return this.http.get<Site>(`/api/sites/${id}`); }

  missionsBySite(siteId: string): Observable<Mission[]> {
    return this.http.get<Mission[]>('/api/missions', { params: { siteId } });
  }
  mission(id: string): Observable<{ mission: Mission; areas: any[]; jobs: any[] }> {
    return this.http.get<any>(`/api/missions/${id}`);
  }
  createMission(body: { name: string; siteId: string; polygonGeoJson: string; altitudeM: number; lineSpacingM: number; speedMps: number; scanMode: string; missionType?: string; cameraModule?: string; surveyEnvironment?: string }): Observable<Mission> {
    return this.http.post<Mission>('/api/missions', body);
  }
  launchInspection(id: string, body: { inspectAltM: number; speedMps: number }) {
    return this.http.post<Mission>(`/api/missions/${id}/launch-inspection`, body);
  }
  plants(id: string) { return this.http.get<import('./models').Plant[]>(`/api/missions/${id}/plants`); }
  sightings(id: string) { return this.http.get<import('./models').Sighting[]>(`/api/missions/${id}/sightings`); }
  setPlantExcluded(id: string, plantId: string, excluded: boolean) {
    return this.http.post<import('./models').Plant[]>(`/api/missions/${id}/plants/${plantId}/excluded`, { excluded });
  }
  createFollowUp(id: string, body: { name?: string }) {
    return this.http.post<Mission>(`/api/missions/${id}/follow-up`, body);
  }
  rescanDetection(id: string, body: { polygonGeoJson: string; altitudeM: number; speedMps: number }) {
    return this.http.post<Mission>(`/api/missions/${id}/rescan-detection`, body);
  }
  reportUrl(id: string) { return this.http.get<{ reportUrl: string }>(`/api/missions/${id}/report`); }
  launchCrude(id: string) { return this.http.post<Mission>(`/api/missions/${id}/launch-crude`, {}); }
  refine(id: string, body: { polygonGeoJson: string; altitudeM: number; lineSpacingM: number; speedMps: number; terrainFollowing: boolean; scanMode: string; standoffM: number; tiltDeg: number }) {
    return this.http.post<{ mission: Mission; routeGeoJson: string; routeLengthM: number;
      routeLineCount: number; estDurationS: number }>(`/api/missions/${id}/refine`, body);
  }
  launchDetail(id: string) { return this.http.post<Mission>(`/api/missions/${id}/launch-detail`, {}); }
  abort(id: string, reason: string) { return this.http.post<Mission>(`/api/missions/${id}/abort`, { reason }); }
  preview(id: string) { return this.http.get<{ previewUrl: string }>(`/api/missions/${id}/preview`); }
  crudeCloud(id: string) { return this.http.get<{ cloudUrl: string; crs: string }>(`/api/missions/${id}/crude-cloud`); }
  missionPhotos(id: string) { return this.http.get<Array<{ url: string; name: string }>>(`/api/missions/${id}/photos`); }
  runSelfTest(id: string) { return this.http.post<{ status: string }>(`/api/missions/${id}/self-test`, {}); }
  latestSelfTest(id: string) { return this.http.get<{ report: string; at: string }>(`/api/missions/${id}/self-test/latest`); }
  flightLogs(id: string) { return this.http.get<Array<{ url: string; name: string }>>(`/api/missions/${id}/logs`); }
  deliverables(id: string) { return this.http.get<Asset[]>(`/api/missions/${id}/deliverables`); }
  siteAssets(siteId: string) { return this.http.get<Asset[]>(`/api/sites/${siteId}/assets`); }
  assetUrls(assetId: string) { return this.http.get<AssetUrls>(`/api/assets/${assetId}/urls`); }
  asset(assetId: string) { return this.http.get<Asset>(`/api/assets/${assetId}`); }
  uploadEditedAsset(assetId: string, las: Blob) {
    return this.http.post<{ jobId: string }>(`/api/assets/${assetId}/edited`, las,
      { headers: { 'Content-Type': 'application/octet-stream' } });
  }
  publishAsset(assetId: string) { return this.http.post<Asset>(`/api/assets/${assetId}/publish`, {}); }

  /** Field-export bundle: authed download (plain <a href> cannot send the JWT). */
  async exportMission(id: string, missionName: string): Promise<void> {
    const res = await fetch(`/api/missions/${id}/export`, {
      headers: { Authorization: `Bearer ${this.auth.accessToken}` },
    });
    if (!res.ok) throw new Error(`export failed: ${res.status}`);
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `skyscan_${missionName.replace(/[^A-Za-z0-9_-]/g, '_')}.zip`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  importMission(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ missionId: string; siteId: string; missionName: string;
      objects: number }>('/api/missions/import', form);
  }

  // ---- drone check (maintenance channel, staff only) ----
  runDroneSelfTest() { return this.http.post<{ ok: boolean }>('/api/drone/self-test', {}); }
  droneSelfTestLatest() { return this.http.get<{ report?: string }>('/api/drone/self-test/latest'); }
  startHoverTest(body: { heightM: number; durationS: number }) {
    return this.http.post<{ ok: boolean }>('/api/drone/hover-test', body);
  }
  abortHoverTest() { return this.http.post<{ ok: boolean }>('/api/drone/hover-test/abort', {}); }
  droneEvents(): EventSource {
    return new EventSource(`/api/drone/events?access_token=${this.auth.accessToken}`);
  }

  /** SSE live stream; EventSource cannot set headers, so the token rides as a query param. */
  missionEvents(missionId: string): EventSource {
    return new EventSource(`/api/missions/${missionId}/events?access_token=${this.auth.accessToken}`);
  }
}
