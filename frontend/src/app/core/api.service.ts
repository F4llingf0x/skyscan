import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Asset, AssetUrls, Mission, Site } from './models';
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
  createMission(body: { name: string; siteId: string; polygonGeoJson: string; altitudeM: number; lineSpacingM: number; speedMps: number; scanMode: string }): Observable<Mission> {
    return this.http.post<Mission>('/api/missions', body);
  }
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

  /** SSE live stream; EventSource cannot set headers, so the token rides as a query param. */
  missionEvents(missionId: string): EventSource {
    return new EventSource(`/api/missions/${missionId}/events?access_token=${this.auth.accessToken}`);
  }
}
