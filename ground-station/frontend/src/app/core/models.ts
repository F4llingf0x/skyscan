export interface UserInfo { username: string; displayName: string; role: 'ADMIN' | 'OPERATOR' | 'CUSTOMER'; }
export interface Site {
  id: string; name: string; description: string; organization: string;
  centerLat: number; centerLon: number; createdAt: string;
}
export type MissionState =
  | 'IDLE' | 'AREA_SELECTED' | 'CRUDE_MISSION_UPLOADED' | 'CRUDE_FLYING' | 'CRUDE_LANDED'
  | 'PREVIEW_READY' | 'DETAIL_SELECTED' | 'DETAIL_MISSION_UPLOADED' | 'DETAIL_FLYING'
  | 'DETAIL_LANDED' | 'PROCESSING' | 'COMPLETE' | 'RETURN_TO_HOME' | 'ABORTED' | 'FAILED';

export type ScanMode = 'LANDSCAPE' | 'STRUCTURE';

export type MissionType = 'SURVEY_3D' | 'CROP_HEALTH' | 'WILDLIFE_SURVEY';
export interface Sighting {
  id: string; label: string; species: string; confidence: number;
  lat: number; lon: number; sightedAt: string; source: 'RGB'|'THERMAL'|'FUSED';
  bodyTempC?: number; photoUrl?: string;
}
export interface PlantFinding { diseaseId: string; diseaseName: string; severity: 'LOW'|'MEDIUM'|'HIGH'; confidence: number; }
export interface Plant {
  id: string; label: string; kind: 'CROP' | 'TREE' | 'CROP_FIELD'; lat: number; lon: number;
  heightM: number; canopyDiaM: number; healthIndex: number; status: string;
  excluded: boolean; photoUrl?: string; findings: PlantFinding[];
}
export interface Mission {
  id: string; name: string; siteId: string; siteName: string;
  state: MissionState; scanMode: ScanMode; missionType: MissionType; cameraModule?: string;
  surveyEnvironment?: 'OPEN' | 'FOREST';
  hasReport: boolean; parentMissionId?: string;
  lastError?: string; createdAt: string; updatedAt: string;
}
export interface Telemetry {
  missionId: string; at: string; lat: number; lon: number; altM: number;
  speedMps: number; batteryPct: number; progressPct: number; flightMode: string;
}
export interface Asset {
  id: string; missionId: string; name: string; pointCount: number; crs: string;
  minX: number; minY: number; minZ: number; maxX: number; maxY: number; maxZ: number;
  status: string; createdAt: string;
}
export interface AssetUrls { copcUrl: string; lasUrl: string; lazUrl: string; asset: Asset; }
export interface ScanAreaInfo {
  passType: 'CRUDE' | 'DETAILED';
  polygonGeoJson: string;
  altitudeM: number;
  lineSpacingM: number;
  speedMps: number;
  terrainFollowing: boolean;
  routeJson?: string;
}

export const HAPPY_PATH: MissionState[] = [
  'AREA_SELECTED', 'CRUDE_MISSION_UPLOADED', 'CRUDE_FLYING', 'CRUDE_LANDED', 'PREVIEW_READY',
  'DETAIL_SELECTED', 'DETAIL_MISSION_UPLOADED', 'DETAIL_FLYING', 'DETAIL_LANDED', 'PROCESSING', 'COMPLETE',
];
