# SkyScan — PROJECT CONTEXT (LLM handoff file)

> **MAINTENANCE RULE — READ FIRST.** This file is the single source of truth for
> continuing work on SkyScan in a fresh session (human or LLM). **Every change
> to the project MUST be reflected here in the same working session**: update
> the affected section(s) and the "Current state & change log" at the bottom.
> If code and this file disagree, the code is right — fix this file.

Last updated: 2026-11-14 (v21.3 — repo split into ground-station/ + aerial-building-scanner/)

---

## 1. What this project is

SkyScan is a drone LiDAR building/terrain scanning service with a two-pass
workflow: an operator draws a zone on a map, the drone flies an autonomous
**crude** coverage pass, the resulting point cloud is previewed, the operator
refines a detail zone, the system computes a **detailed** flight plan, the
drone flies it (avoiding — and additionally scanning — unexpected static
obstacles), and a C++ engine processes raw captures into LAS/LAZ/COPC
deliverables shown in an embedded web viewer.

The whole stack runs on a laptop with `docker compose up --build` using a
**simulated drone** (StubDroneConnector). Real hardware plugs in behind the
same interface (`DRONE_CONNECTOR=jetson`), served by a Python agent on the
Jetson Orin Nano.

## 2. Repository map

```
skyscan/ground-station/   (repo root also holds ../aerial-building-scanner/)
├── frontend/          Angular 17 standalone app (nginx :4200, /api proxied)
│   └── src/app/       pages/ (dashboard, sites-map, site, mission, planner,
│                      asset-viewer, login, cloud-editor-demo), viewer/
│                      (three.js point-cloud viewer, LAS loader/writer, cloud
│                      review + editor components), core/ (auth, api, geo, models)
├── backend/           Spring Boot 3 (Java 17, :8080), PostgreSQL + MinIO
│   └── com.skyscan/   auth/ (JWT), domain/ (JPA), repo/, site/, mission/
│                      (MissionService = orchestrator + single state writer,
│                      MissionStateMachine = explicit transition table,
│                      RoutePlanner = lawnmower + structure-orbit planners,
│                      MissionController), processing/ (engine job dispatch +
│                      callback), storage/ (MinIO presigned URLs), live/ (SSE),
│                      connector/ (DroneConnector boundary: stub/ + jetson/),
│                      agri/ (DiseaseCatalog + CropAnalysisService: plant
│                      detection, inspection route, disease analysis, report),
│                      transfer/ (field export/import), config/, dto/, seed/
├── engine/            C++17 point-cloud pipeline (:9090): clean, downsample,
│                      georeference (STUB), SLAM/co-registration (STUB), merge,
│                      LAS/LAZ/COPC writers; presigned GET/PUT, callback POST
├── (drone agent)      MOVED to ../aerial-building-scanner/legacy_drone_agent/ —
│                      Jetson Orin Nano companion agent (Python 3.10+)
│   ├── skyscan_drone/ agent.py (command loop, wiring), route_executor.py,
│   │                  mavlink_controller.py (pymavlink, ArduPilot GUIDED),
│   │                  scan_assembler.py (lidar→LAS georeferencing),
│   │                  las_writer.py, mission_store.py (resume), selftest.py,
│   │                  flight_recorder.py, backend_client.py,
│   │                  hover_test.py (GPS-free hover & dodge: GUIDED_NOGPS +
│   │                  SET_ATTITUDE_TARGET, PI+damping alt loop on rangefinder,
│   │                  windowed step-count closing detection, tilt-away dodge,
│   │                  hard-min land — physics-sim verified),
│   │                  lidar_bench.py (standalone tilt-lidar bench tester:
│   │                  encoder vs estimated tilt, selectable gear ratio/limit),
│   │                  camera_bench.py (IMX219-77 module test: pipeline, latency,
│   │                  resolution, geotag, cadence, footprint/GSD lens check),
│   │                  vision_bench.py (full chain: camera/dir/mock -> detect ->
│   │                  project/dedup -> classify -> mini report; engine acceptance),
│   │                  safety/ (monitor.py veto + avoidance.py layered),
│   │                  sensors/ (rplidar_c1.py, tilt_servo.py DS3218+AS5600,
│   │                  rangefinder.py TFmini-S ×2, camera.py IMX219,
│   │                  camera_modules.py FOV catalog per lens variant,
│   │                  thermal.py FLIR Lepton/PureThermal UVC + hotspot
│   │                  extraction (mock+real-stub), i2c_mux.py TCA9548A)
│   │                  vision/ (detector.py PlantDetector YOLO/TensorRT+mock +
│   │                  PlantAggregator frame→world→dedup; classifier.py
│   │                  DiseaseClassifier CNN/ViT+mock; animal_detector.py
│   │                  AnimalDetector YOLO+mock + SightingTagger RGB↔thermal
│   │                  fusion + space-time dedup + time/location tagging)
│   ├── config.yaml    ALL hardware/tuning knobs (see §5)
│   ├── README.md      full hardware doc: mount planes, avoidance, GNSS ladder,
│   │                  power tree, ArduPilot-vs-PX4 decision, bring-up checklist
│   ├── SETUP_MANUAL.md staged bring-up manual: FC params, Jetson setup, wiring,
│   │                  config, SITL & bench verification (mirrors selftest.py
│   │                  component names), calibration, first flights, ops, troubleshooting
│   ├── sitl/run_sitl.sh   ArduPilot SITL + SKYSCAN_MOCK_SENSORS=1
│   └── systemd/       skyscan-drone.service
├── sample-data/       demo LAS files + photos used by the stub connector
├── FIELD_DEPLOYMENT.md offline site-LAN operation (router + laptop + drone, no
│                      internet) + export/import sync to the central instance
├── docker-compose.yml postgres, minio, backend, engine, frontend
└── PROJECT_CONTEXT.md THIS FILE — keep updated with every change
```

## 3. Architecture & contracts (stable — change deliberately)

- UI ↔ backend: REST + SSE under `/api`, JWT (roles OPERATOR/CUSTOMER/ADMIN).
- **Mission state machine** (single writer = MissionService): IDLE →
  AREA_SELECTED → CRUDE_MISSION_UPLOADED → CRUDE_FLYING → CRUDE_LANDED →
  PREVIEW_READY → DETAIL_SELECTED → DETAIL_MISSION_UPLOADED → DETAIL_FLYING →
  DETAIL_LANDED → PROCESSING → COMPLETE; plus RETURN_TO_HOME → ABORTED, FAILED.
- **DroneConnector boundary** (the stub/real seam): `uploadMission(MissionPlan)`,
  START/RESUME/ABORT; listener receives events + scan/photo uploads.
  `MissionPlan` carries routeJson (3D GeoJSON LineString [lon,lat,relAltM]),
  passType, sensorType, altitudeM, speedMps, **tiltDeg** (fixed per flight).
  Real side: `JetsonDroneConnector` + `DroneGatewayController`
  (`/internal/drone/*`, X-Internal-Token) polled by the Jetson agent.
- **Field transfer** (`transfer/`): GET /api/missions/{id}/export streams a
  portable ZIP (manifest.json + objects/<key> for every MinIO object); POST
  /api/missions/import re-creates site+mission with NEW ids, rewriting the old
  mission UUID inside object keys. Staff only. UI: Export bundle on the
  mission page, Import field bundle on the dashboard. The stack itself runs
  fully offline (see FIELD_DEPLOYMENT.md); frontend degrades gracefully
  without internet (tile fallback grid + localStorage['skyscan.tileUrl']
  override; dashboard map / Potree / fonts already had fallbacks).
- Backend ↔ engine: POST /jobs {jobId, inputs[{sensorType, passType, getUrl}],
  outputs{lasPutUrl, lazPutUrl, copcPutUrl}, targetCrs, callbackUrl} → 202 →
  presigned PUTs → callback with X-Internal-Token. Engine holds no credentials.
- SensorType enum: FIXED_3D (crude), SPINNING_TILTED (landscape detail),
  FRONT_360_VERTICAL (structure detail). One physical sensor — the tilt angle
  and pattern distinguish modes (see §4).
- RoutePlanner: `plan(...)` = boustrophedon lawnmower (optimal sweep
  orientation, even–odd polygon clipping, serpentine order); `planStructure(...)`
  = footprint offset by standoff, stacked orbit loops every levelSpacingM up
  to topHeightM (stacked spiral, 3D coordinates).

## 3b. Mission types (extensible — more drone activities planned)

`Mission.missionType` (enum `MissionType`) selects the activity; all types
reuse the SAME state machine, DroneConnector boundary, storage, SSE and
battery-swap/resume machinery, branching only at the crude/detail-landed hooks
in MissionService and at the sensor-type switch in the drone route executor.

- **SURVEY_3D** (default): the original two-pass LiDAR point-cloud survey.
- **WILDLIFE_SURVEY** (v16): SINGLE-PASS animal search (animals move — no
  detail pass; FSM gained CRUDE_LANDED→PROCESSING for this). Coverage flight
  = the crude lawnmower with SensorType WILDLIFE_CAM. On each frame the agent
  runs AnimalDetector (YOLO→TensorRT stub | mock) on RGB + ThermalCamera
  hotspot extraction (Lepton/PureThermal UVC, capture TODO(hardware), mock
  real+tested), then SightingTagger fuses them: RGB det near a hotspot →
  FUSED (+0.2 conf, bodyTempC), lone hotspot → THERMAL "unidentified (warm
  body)", and dedups MOVING targets by species + merge_dist_m (12) +
  merge_window_s (90) — merged re-sightings keep the FIRST time-of-finding.
  Every tag = species, confidence, lat/lon, sightedAt, source, bodyTempC.
  Tags stream LIVE during the pass (agent → POST /internal/.../sightings/live
  → SSE "animal" event → wildlife panel appends rows in real time) and the
  full list uploads at landing (POST /internal/.../sightings). Backend
  WildlifeService ingests (drone data preferred, deterministic stub fallback
  for the simulated connector), builds HTML+JSON report (species tally,
  time-ordered tag table) at reports/{id}/wildlife_report.*, mission →
  PROCESSING → COMPLETE in one pass. Endpoints: GET /api/missions/{id}/
  sightings + shared /{id}/report. UI: planner option (camera-module dropdown
  applies — projection), wildlife-panel.component (live map markers colored
  by source + table + report link). Bench: vision_bench --task animals.
  Domain: AnimalSighting entity. Config: thermal.* + vision.animal_detector.*
  + vision.tagger.*.
- **CROP_HEALTH**: agronomy. Crude pass → CropAnalysisService.detectPlants()
  (with an optional detection RESCAN loop at PREVIEW_READY: sub-polygon
  reflights merge missed plants until the operator verifies completeness —
  the detection flight is a ONE-TIME cost; follow-ups reuse the plant list)
  finds every crop plant + tree (Plant rows, shown as map markers at
  PREVIEW_READY). Operator launches the inspection pass (POST
  /{id}/launch-inspection): buildInspectionRoute() orders the plants
  (nearest-neighbor) into a low/slow GeoJSON route flown as the DETAILED pass
  with sensorType PLANT_INSPECT. The drone route executor hovers at each plant
  and captures a close-up burst (config `inspection.shots_per_plant`,
  `dwell_s`); battery swaps between plants are handled by the existing leg loop
  + resume. Detail landed → analyzeInspection() attaches PlantFinding rows from
  DiseaseCatalog, buildAndStoreReport() renders HTML+JSON to
  reports/{id}/crop_report.*, mission → COMPLETE. UI: crop-panel.component
  (plant map + table, launch button, report link); GET /{id}/plants,
  GET /{id}/report.
  MODELS (v15): detection + classification run ON THE DRONE via pluggable
  interfaces (legacy_drone_agent/skyscan_drone/vision/). PlantDetector = YOLO→TensorRT
  (impl stub, load/infer marked TODO) or mock; PlantAggregator does the REAL
  frame→world projection (camera FOV + AGL + yaw) + cross-frame dedup, turning
  boxes into georeferenced plants. DiseaseClassifier = CNN/ViT→TensorRT (stub)
  or mock, fed the per-plant close-up burst. Agent uploads plants (crude) and
  findings (inspection) to /internal/drone/missions/{id}/plants|findings;
  MissionService PREFERS drone data and falls back to the backend stub detector/
  analyzer only when the drone supplied none (i.e. the simulated connector).
  Backend disease IDs (DiseaseCatalog) and drone classifier IDs
  (vision/classifier.py DISEASE_IDS_*) MUST stay in sync.
  CAMERA MODULE is SELECTABLE (v15.2): airframe default = config camera.module;
  per-mission override = Mission.cameraModule, picked in the planner before the
  scan, carried in MissionPlan -> agent, resolved via
  sensors/camera_modules.py (imx219-std 62.2° | imx219-77 default | imx219-120
  wide/degraded | imx219-160 fisheye/unsuitable — wide variants warn loudly).
  FOV drives PlantAggregator projection: wrong module = misplaced plants
  (~1.3 m at frame edge from 40 m between std and 77). The planner dropdown
  (planner.component.ts) MUST stay in sync with the drone catalog ids. Config:
  vision.detector/classifier.backend = mock|tensorrt, engine_path, FOV, dedup.
  Report carries an "advisory only, confirm with an agronomist" disclaimer.
  OPTIONAL off-flight LLM narrative: ReportNarrator (backend), config-gated
  (skyscan.agri.narrator.*, disabled by default), any OpenAI-compatible
  endpoint; writes prose FROM the structured findings only (does NOT make the
  disease calls) and degrades to the structured report on any failure.

To add another activity: add a MissionType, a sensor type if the payload
differs, branch the two landed hooks in MissionService, add any executor
behavior keyed off sensorType, and (optionally) a side panel component.

## 4. Drone hardware & the geometry decisions (IMPORTANT)

Airframe: quad, 4S. FC: **Pixhawk 6C Pro, ArduPilot Copter** (decision made —
see legacy_drone_agent/README "Firmware" section; PX4 only if moving to full ROS 2 offboard
later; only mavlink_controller.py would change). Companion: **Jetson Orin
Nano**. Lidar: **RPLidar C1** — 2D, 360°, 12 m, on a ±30° tilt axis
(DS3218 servo + AS5600 encoder, closed loop; **tilt is FIXED per flight**,
measured angle is what georeferencing uses). Rangefinders: TFmini-S ×2
(down = altimeter/landing; forward = avoidance). Camera: IMX219 CSI.
I2C fan-out: TCA9548A mux (+ PCA9685 for servo PWM).

### Lidar mount plane — config switch `lidar.plane` (v11)
- **vertical** (DEFAULT, survey-optimal): sensor at the NOSE, spin axis along
  body X; ring covers up/right/down/left (angle 0°=up). Pushbroom: crude
  lawnmower profiles terrain below + both sides; structure scans fly TANGENT
  orbits (nose-forward — ArduPilot's default GUIDED yaw) so the facade sits
  ABEAM inside the scan plane and is painted floor-to-roof every sweep; one
  orbit level covers ±√(range²−standoff²) of facade height. Tilt = pitch of
  the plane about body Y; + leans ring TOP toward the nose (+30 eaves from
  below, −30 roofs / forward-down terrain look-ahead). **Blind fore/aft →
  forward TFmini-S REQUIRED (self-test FAILs without it).**
- **horizontal** (avoidance-optimal): sensor at the CENTER, spin axis vertical;
  full 360° obstacle ring at flight altitude (angle 0°=nose) → 72×5°-sector
  OBSTACLE_DISTANCE to the FC, BendyRuler (OA_TYPE=1) capable. Scanning
  coverage per pass is a narrow band → several× more orbit levels/lines.
  Tilt + lifts the NOSE ray. Forward TFmini-S optional (fused via min).
- Invariant in BOTH mounts: the lateral rays lie ON the tilt axis → left/right
  ranging is tilt-immune. Switching planes: change config, re-measure
  `lidar.mount.offset_m`, recalibrate `angle_zero_deg` (UP vs NOSE).
- Everything downstream keys off the switch: rplidar_c1 digests, scan_assembler
  projection, agent FC injection, avoidance forward source, self-test severity.

### Scan georeferencing chain
LidarPoint(t, in-plane angle, dist) → tilt rotation (MEASURED encoder deg) →
mount extrinsics → body→NED (ZYX from time-interpolated ATTITUDE) → local UTM
(EPSG:32634 hardcoded helper in scan_assembler._utm34n) via interpolated
GLOBAL_POSITION_INT → LAS 1.2. Pose interpolation: MavlinkController.pose_at().

### Avoidance (layered, no operator)
1. FC layer: forward TFmini + ring wedges as per-orientation DISTANCE_SENSOR
   (PRX1_TYPE=2, AVOID_ENABLE=7); horizontal mount adds OBSTACLE_DISTANCE ring.
   Works even if the Jetson dies.
2. Companion: stop-and-hold w/ hysteresis (stop_distance_m / resume_clear_m,
   3 s clear), lateral corridor guard (lateral_stop_m) on the ring's side rays.
3. Escalation after detour_after_s: locate blocker (bearing×range×pose), fly a
   1.5-loop arc at stop_distance+detour_margin — capture RUNNING, obstacle is
   scanned into the deliverable — rejoin route. Events OBSTACLE_*.
4. Geofence: mission polygon + margin checked at every waypoint incl. detours.
Multi-flight: battery legs, atomic progress persist, RTL at rtl_pct, resume
from exact waypoint after swap; land_now_pct hard floor.

### Wiring: Pixhawk 6C Pro ↔ Jetson Orin Nano
The 6C Pro's serial comms ports are TELEM1/2/3 (GPS1/2 are UARTs reserved for
GNSS; CAN/POWER/SBUS/SAFETY/PWM/DEBUG are not comms UARTs). **Use TELEM2**
(= ArduPilot SERIAL2), 6-pin JST-GH: pin1 VCC5V (DO NOT CONNECT), pin2 TX →
Jetson pin10 (UART1 RX, /dev/ttyTHS1), pin3 RX ← Jetson pin8 (UART1 TX),
pin6 GND ↔ Jetson pin6. 3.3 V logic both sides, no level shifter. FC params:
SERIAL2_PROTOCOL=2, SERIAL2_BAUD=921, BRD_SER2_RTSCTS=0. Jetson: disable
nvgetty, add user to dialout. TELEM1 = SiK ground-station radio. USB only for
bench setup, never for flight. Jetson power: own 12 V buck from battery
(never the TELEM 5 V pin); tilt servo on its own isolated 6 V UBEC.

### GNSS decision ladder (legacy_drone_agent/README has the full table)
M8N (shake-down/crude only, metre-class smear) → Unicore UM980/UM982 (~€120–150,
**recommended sweet spot**) or Quectel LC29H (~€40–60, bench-verify) → ZED-F9P
(mature ecosystem). RTK corrections: NTRIP over LTE (Hungary: GNSSNET.hu or
rtk2go). PPS→Jetson GPIO strongly recommended (10 ms @ 4 m/s = 4 cm smear).

## 5. Key config (legacy_drone_agent/config.yaml)

backend{base_url, internal_token, poll_interval_s} · mavlink{device=/dev/ttyTHS1,
baud=921600, vehicle=arducopter} · flight{cruise_speed_mps, waypoint_accept_radius_m,
takeoff_alt_m, geofence_margin_m, battery{leg_start_min_pct=90, rtl_pct=30,
land_now_pct=18}, resume_file} · lidar{port, **plane: vertical|horizontal**,
scan_hz, max_range_m=12, angle_zero_deg, wedge_half_deg=25, mount{offset_m,
tilt_zero_deg}} · tilt{pca9685_channel, i2c_bus=7, encoder_mux_channel,
min/max_deg=±30, settle_tolerance_deg, landscape_deg=-30, structure_deg=0} ·
rangefinder{down{...uart /dev/ttyTHS2}, forward{enabled, i2c mux_channel=1}} ·
camera{...} · avoidance{stop_distance_m=4, resume_clear_m=6, lateral_stop_m=2.5,
detour_after_s=8, detour_margin_m=3} · logging{dir, state_hz} · output{scan_dir,
target_crs=EPSG:32634}

## 6. How to run / test

- Full stack, no hardware: `docker compose up --build` → UI :4200
  (operator/password), API :8080/swagger-ui.html, MinIO console :9001.
- Drone agent in SITL: `legacy_drone_agent/sitl/run_sitl.sh` (ArduPilot SITL) +
  `SKYSCAN_MOCK_SENSORS=1 python3 -m skyscan_drone.agent --config config.yaml`
  with mavlink.device=udp:127.0.0.1:14550; backend env `DRONE_CONNECTOR=jetson`.
- Mock sensors synthesize per-plane scenes (vertical: ground 5 m below +
  facade 8 m right; horizontal: wall 8 m off the nose).
- Geometry sanity: probe scan_assembler with single LidarPoints (unit-style
  checks used during v10/v11: right ray lands abeam, ±30° tilt moves top/nose
  ray, lateral rays tilt-immune, LAS magic `LASF`).

## 7. Known stubs / gaps / next work (honest list)

- Engine georeferencing + SLAM/co-registration are STUBS — scan-matching here
  would recover relative consistency from cheap GNSS; do regardless of RTK.
- `_utm34n` hardcodes UTM 34N (fits Hungary); generalize to `output.target_crs`.
- Forward coverage is one narrow beam (vertical mount): consider a second
  TFmini angled up, or a depth camera, for wires/eaves.
- Hardware still missing for real flight (legacy_drone_agent/README gap list): RTK GNSS,
  RC link + failsafe (868 MHz in EU, not 915!), LTE backhaul, PM02D power
  module, Remote ID, strobe, PPS sync, vibration-isolated sensor plate.
- Camera geotag EXIF is minimal; photo→facade association not implemented.
- No automated test suite; verification is SITL + the smoke probes above.
- Frontend cloud editor is demo-grade; measurement tools basic.

## 8. Current state & change log (append here — newest first)

- **v21.3 (2026-11-14)**: REPO SPLIT. Everything here moved under
  `ground-station/` (UI, backend, post-processing engine, sample data, compose,
  docs); relative paths inside (compose, application.yml) are unchanged. The
  drone agent moved to `../aerial-building-scanner/legacy_drone_agent/`, next to
  the new Aerial Building Scanner project (digital twin, drone logic, tests).
  Deploy paths such as /opt/skyscan/drone are unchanged.
- **v21.2 (2026-07-17)**: Deliverables card gating restored. The v19 "hide
  point-cloud UI for non-survey missions" edit had only half-applied to the
  Deliverables card in mission.component.ts: the closing `}` was inserted but
  the opening `@if (isSurvey()) {` was lost — the orphan brace broke the first
  docker frontend build (NG5002), and the initial fix (delete the brace) made
  crop/wildlife missions show the point-cloud Deliverables card again. Now
  properly wrapped in `@if (isSurvey())`; ng build clean. (Same lost-edit
  failure class as v17.1/v19 — the duplicate/symbol audits don't catch a lost
  BLOCK OPENER whose closer survives; the NG5002 error location pointed at the
  closer, far from the real gap.) Full-page audit for point-cloud UI on
  non-survey missions: mission page (preview/cloud editor/rescan/deliverables
  all isSurvey-gated), crop+wildlife panels, dashboard, planner clean; site
  page Deliverables table is data-driven (crop/wildlife create no assets —
  verified [] via API on the seeded demos). SURVEY RESCAN VERIFIED LIVE in the
  docker stack (stub connector): PREVIEW_READY → refine → detail flight →
  COMPLETE (69,780-pt deliverable) → refine again FROM COMPLETE (→
  DETAIL_SELECTED, the rescan edge) → rescan flight → COMPLETE with a NEW
  merged deliverable (131,560 pts = all acquisitions re-merged); the mission
  page's rescan selector loads assets[0] = that newest cloud.
- **v21.1 (2026-07-17)**: build fixes from the first real v18–v21 Maven run.
  (1) CropAnalysisService had TWO mergeDetectedPlants definitions: one written
  before a context compaction, one re-written after it (presence-based audits
  can't catch duplicates). Kept the better pre-compaction version (kind-matched
  dedup + position refinement on re-detection + confidence max); deleted the
  simpler duplicate. (2) MissionService was missing `import java.util.List`
  (v19 createFollowUp — v19/v20 were never Maven-built so it only surfaced
  now). Added a project-wide DUPLICATE-DEFINITION scan (Java signatures,
  Python defs per scope, TS class members) to the audit toolkit: clean.
  RULE: after any session restore/compaction, scan for duplicates before
  inserting code — and audits must count definitions, not just check presence.
- **v21 (2026-07-17)**: DETECTION RESCAN LOOP + WILDLIFE FOREST MODE.
  (1) Rescan loop (user: "if not all plants found, send the drone back"):
  ScanArea.rescanPolygonGeoJson; POST /api/missions/{id}/rescan-detection
  {polygonGeoJson, altitudeM, speedMps} at PREVIEW_READY (crop only) →
  PREVIEW_READY→CRUDE_MISSION_UPLOADED (edge pre-existed) → planRescan()
  flies ONLY the sub-polygon → crude-landed crop branch: MERGE instead of
  replace — drone path via ingestDronePlants (rescan-aware, tracked by
  in-memory dronePlantsThisFlight set) → crop.mergeDetectedPlants (dedup 2 m
  vs existing, CROP_FIELD radius-aware, labels continue numbering), stub path
  via detectMorePlants; rescan polygon cleared; back to PREVIEW_READY
  ("N total after rescan — verify, rescan again, or launch inspection").
  Loop repeats until the operator verifies completeness. UI: crop panel
  "Missed plants? Rescan an area…" → draw sub-polygon (red) on the plant map
  → "Fly detection rescan". The detection flight thus stays a ONE-TIME cost:
  follow-ups (v19) reuse the verified plant list forever after.
  (2) Wildlife FOREST mode: Mission.surveyEnvironment (OPEN|FOREST, planner
  Terrain select on the wildlife branch) → MissionPlan → agent: FOREST sets
  tagger thermal_only_conf=forest_thermal_conf (0.6) and
  merge_dist_m=forest_merge_dist_m (20 m — the same animal glimpsed through
  several canopy gaps merges into one tag), FOREST_MODE event; config knobs
  documented. Planner hints: dawn/dusk, leaf-off season, expect
  "unidentified (warm body)". Under-canopy flight explicitly NOT offered
  (research-grade autonomy; our GPS-free hover/avoidance is a building block
  only).
- **v20 (2026-07-17)**: CROP_FIELD ZONES (user-clarified: crop fields = zones
  with zone-level health). PlantKind += CROP_FIELD (center lat/lon + diameter
  in canopyDiaM, label F-###). Detection: drone PlantAggregator._coalesce_fields
  = grid-accelerated single-link clustering of CROP entries (config
  field_link_dist_m 3 m / field_min_plants 25) — dense patch -> ONE zone
  (verified: 64 plants @2 m -> one 19.8 m zone; 8 m orchard row + trees stay
  individual); backend stub detector carves 1-2 zones from the polygon and
  keeps individual plants outside them. Inspection: CropAnalysisService.routePlan()
  is THE deterministic waypoint list (NN order; each zone expands to 3..7
  sample points scaled by diameter on a fixed compass cross, verified in-zone)
  — SHARED by buildInspectionRoute, ingestFindings and attachPlantPhoto so
  route indices always map back to the right entity. Zone-level health:
  per-sample findings aggregate to ONE finding per disease per zone with note
  "detected at k of n sample points", severity = max(coverage-rank, worst
  sample), zone healthIndex = clean-sample fraction; stub analyzer mirrors
  this. Report: "crop field zones" card, coverage note in the findings table,
  "Ø m zone" in the inventory. UI: zones render as translucent meter-true
  circles (never clustered), F- labels, zone tally, "ZONE Ø.. m" kind cell;
  classifier maps CROP_FIELD to the crop disease pool. Exclusion + follow-up
  copy + photos all work for zones (photo = first sample's close-up).
  NOTE: heredoc failure mid-chain hid an unapplied detector edit — caught by
  on-disk verification; rule hardened: verify ON DISK after every write.
- **v19 (2026-07-16)**: product feedback round. (1) MAP DECLUTTER: map-draw
  markers now grid-cluster in screen space (~36 px cells) into count bubbles
  (click zooms into the group), re-clustered on zoom/pan; single markers get
  popups. (2) PHOTOS: Plant.photoObjectKey + AnimalSighting photos wired
  end-to-end — real path: route executor's per-plant burst[0] uploaded by
  index (/internal/.../plants/by-index/{idx}/photo), wildlife evidence frame
  per tag (Sighting.frame_path → /sightings/{label}/photo); stub/demo path:
  PlaceholderImage cards ("SIMULATED IMAGE" watermark) attached in
  analyzeInspection/generateStubSightings/ingestOne. photoUrl presigned in
  /plants and /sightings responses; thumbnails in both panels; photo popups on
  map markers. (3) EXCLUSIONS: Plant.excluded + POST /{id}/plants/{pid}/excluded;
  checkbox column at PREVIEW_READY; inspection route + report skip excluded
  (status EXCLUDED/SKIPPED); launch button counts included. (4) FOLLOW-UPS
  (re-check months later): Mission.parentMission; POST /{id}/follow-up copies
  plants+polygon from a COMPLETE crop mission into a new mission starting at
  PREVIEW_READY (crude pass skipped by design); report gains "Change since
  last inspection" (NEW / PERSISTS(was X)) + "Resolved since last inspection"
  section; "Plan follow-up inspection" button on completed crop missions.
  (5) CLOUDLESS MISSIONS: for CROP_HEALTH + WILDLIFE_SURVEY the lidar flies
  for OBSTACLE AVOIDANCE ONLY — agent skips point assembly and uploads no LAS
  (gateway scan part now optional, ScanUpload.rawScanFile nullable, backend
  skips raw/acquisition rows when absent); stub sends no scan/preview for
  these; mission page hides preview/cloud editor/deliverables for non-survey
  missions (their deliverable is the map + photos + report in their panels).
  Audit caught 2 silently-lost edits (unwritten buffer) — both re-applied.
  OPEN QUESTION for the user: semantics of "crop fields" (see below).
- **v18 (2026-07-16)**: demo cases for the agriculture + wildlife functions.
  (1) SEEDED demos: seed/DemoMissionSeeder (@Order(20), after DataSeeder now
  @Order(10)) creates two COMPLETE missions on boot — "DEMO — Orchard crop
  health" (demo site, plants+findings via the same stub detector/analyzer,
  HTML report in MinIO) and "DEMO — Riverside wildlife survey" (sightings +
  report). Idempotent by mission name; back-fills existing DBs; report upload
  retries 4×3 s for MinIO then skips with a warning. (2) LIVE wildlife demo:
  DroneConnectorListener.onLiveAnimalSighting (default no-op) + MissionService
  impl; onLiveSighting now PERSISTS each live tag (WildlifeService.ingestOne)
  so the landing report matches what streamed; StubDroneConnector emits three
  scripted live sightings (roe deer FUSED / wild boar FUSED / hare RGB) at
  ticks 5/9/13 of WILDLIFE_CAM flights near the vehicle position — the panel
  shows animals appearing mid-flight exactly like real hardware. (3)
  DEMO_GUIDE.md at root: scripted A1/A2 (crop seeded/live), B1/B2 (wildlife
  seeded/live incl. live tagging), drone-check bonus demo, talking-points
  table. README links it. All inserts assert-verified (v17.1 lesson).
- **v17.2 (2026-07-16)**: frontend build fix — Angular 17 block syntax treats a
  bare `@` inside a template as a control-flow block start (NG5002). The
  drone-check obstacle strip used "1.5 m @ 90°"; replaced with `&#64;`. Swept
  every component template for other bare `@` occurrences: clean. RULE for new
  templates: never use a literal `@` in template text — write `&#64;`.
- **v17.1 (2026-07-16)**: first real Maven build fixes. (1) CropAnalysisService
  was missing `import com.fasterxml.jackson.databind.JsonNode` (bodies used the
  bare name, signatures the qualified one). (2) The v17 StubDroneConnector
  hover-simulation insert had silently NO-OP'd (its text anchor didn't exist in
  the file), so the stub didn't implement startHoverTest/abortHoverTest —
  re-inserted against a verified anchor. Lesson encoded: after any anchored
  text replacement, grep for the inserted symbol; a full symbol audit of every
  v14–v17 edit now passes (all expected symbols present, imports verified).
- **v17 (2026-07-16)**: dedicated pre-flight DRONE CHECK page (/drone-check,
  staff nav link) + GPS-FREE hover & dodge test. Drone: mavlink_controller
  gains arm/disarm/set_attitude_target (GUIDED_NOGPS path, no position
  estimate needed); new hover_test.py — climbs to a set height (≤3 m clamp)
  closed-loop on the downward rangefinder (PI + climb-rate damping; pure PI
  overshot in sim and was fixed), holds, and dodges TOWARD-MOVING obstacles:
  per-azimuth windowed range history, closing = windowed rate > threshold AND
  ≥2 distinct decreasing steps in 1 s (rejects ghost-return spikes — plain
  EMA and instantaneous-streak variants both failed in sim and were replaced),
  tilt-away capped at dodge_tilt_deg, obstacle inside min_dist_m ⇒ immediate
  land (never dance around a person at prop distance); stale rangefinder ⇒
  land; battery floor; duration cap; disarm in finally. Obstacle azimuths per
  lidar mount: vertical = left/right ring + forward TFmini (REAR BLIND);
  horizontal = full 72-sector ring. Agent: HOVER_TEST/HOVER_ABORT commands
  (refused while a mission flight is active), events on the maintenance
  channel. Backend: DroneConnector.startHoverTest/abortHoverTest (+ stub
  simulation incl. scripted approach→DODGE→CLEAR), listener
  onDroneCheckEvent, gateway routing, check/ package (CHECK_ID channel,
  /api/drone/self-test|hover-test|events SSE, server-side clamps),
  MissionService maintenance-channel branches (selftest for CHECK_ID stored
  without a mission). Frontend: drone-check page (self-test table + hover
  test with warnings, live AGL bar, obstacle strip, event log). Physics-sim
  verified: stable 1 m hover, ghost ignored, sustained approach dodges away,
  retreat clears, hard-min lands+disarms, ZERO GPS calls. NOT Maven/npm
  built (no network) — first real build: docker compose up --build.
- **v16 (2026-07-16)**: WILDLIFE_SURVEY mission type — map-area animal search
  with RGB + thermal, YOLO-class detection, every animal tagged with time +
  location of finding. FSM: CRUDE_LANDED→PROCESSING (single-pass path). New:
  sensors/thermal.py, vision/animal_detector.py (fusion + space-time dedup,
  fully tested: FUSED boost/temp, THERMAL-only fallback, moved-animal merge
  keeps first-seen time, distant/late = honest new tag), agent WILDLIFE_CAM
  wiring + live sighting streaming, backend_client.upload_sightings +
  post_live_sighting; backend AnimalSighting + WildlifeService (ingest/stub/
  report) + endpoints (internal sightings + sightings/live, public GET
  sightings) + MissionService single-pass branch (drone data preferred);
  frontend planner option + wildlife-panel (SSE live rows) + models/api;
  vision_bench --task animals. Verified end-to-end in mock incl. live-stream
  hook parity (every tag streamed exactly once). NOT built with Maven/npm
  (no network here) — first real build: docker compose up --build.
- **v15.2 (2026-07-16)**: camera module selectable before scanning. New
  sensors/camera_modules.py catalog (single FOV source of truth, mapping-grade
  warnings for wide/fisheye). Drone: agent resolves mission cameraModule >
  config camera.module per flight (CAMERA_MODULE event), aggregator FOV set
  from it; config hfov/vfov now optional custom-lens overrides; both bench
  tools gain --module. Backend: Mission.cameraModule (+DTOs, controller,
  MissionPlan, Jetson serialization). Frontend: module dropdown in the crop
  planner branch, module shown in crop panel. Verified: override precedence,
  unknown-id fallback, warnings, and the concrete projection difference per
  module (~1.3 m at frame edge from 40 m AGL).
- **v15.1 (2026-07-16)**: camera fixed as IMX219 **77° diagonal** variant ->
  hfov 64.9° / vfov 51.0° (4:3), set in config camera.* and vision.aggregator.*
  and detector defaults (projection accuracy depends on it — lens-variant
  tape-measure check documented in SETUP_MANUAL). New bench tools:
  camera_bench.py (module-only: capture path, latency, JPEG/resolution sanity,
  EXIF geotag round-trip, mission-cadence check, footprint/GSD table; exit-coded)
  and vision_bench.py (full recognition+health chain over mock/camera/dir
  sources with per-stage latencies, severity-sorted findings table, JSON out;
  doubles as the TensorRT engine acceptance test via --source dir). Both
  verified in mock (incl. dir + camera sources; dedup: 239 detections -> 62
  plants all multi-hit at min-hits 2).
- **v15 (2026-07-16)**: real CV model interfaces (was: mission-id-seeded stubs).
  Drone: new vision/ package — PlantDetector (YOLO/TensorRT stub + mock) +
  PlantAggregator (REAL frame→world projection + dedup, tested); DiseaseClassifier
  (CNN/ViT TensorRT stub + mock). route_executor gains on_crude_frame (detector
  feed) + on_plant_images (classifier feed) hooks; agent runs detection over the
  crude pass and classification per inspected plant, uploads plants/findings via
  backend_client. config vision.* block. selftest _vision check. Backend: MissionPlan
  + JetsonDroneConnector carry missionType; CropAnalysisService gains
  ingestDetectedPlants/ingestFindings/hasPlants/hasFindings; new CropIngestController
  (/internal/drone/missions/{id}/plants|findings); MissionService prefers drone data
  over stubs. New ReportNarrator (optional off-flight LLM report prose, config-gated,
  graceful fallback) wired into the HTML report; application.yml skyscan.agri.narrator.*.
  Verified: detector→aggregator→dedup (overlap merges, distinct stay separate,
  projection center=nadir/right=east), classifier determinism, agent wiring,
  tensorrt-without-engine clean error. NOT yet built with Maven/npm (no network).
- **v14 (2026-07-16)**: CROP_HEALTH mission type (agronomy). New: MissionType
  enum + Mission.missionType/reportObjectKey; domain Plant, PlantFinding
  (+repos); SensorType.PLANT_INSPECT; agri/ package (DiseaseCatalog with
  cures/checks; CropAnalysisService: stub detection, NN inspection route, stub
  disease analysis, HTML+JSON report). MissionService branches crude-landed
  (→detect→PREVIEW_READY) and detail-landed (→analyze→report→COMPLETE) on
  type; launchInspection() builds the per-plant route; endpoints
  launch-inspection, /plants, /report. Drone route_executor hovers+captures at
  each plant on PLANT_INSPECT (config inspection.shots_per_plant/dwell_s);
  battery swaps between plants use the existing leg/resume path. Frontend:
  mission-type picker (planner), crop-panel component (plant map + table,
  launch, report), map-draw gains static markers input, models/api extended.
  Verified: drone inspection path (hover+capture per plant) and the agronomy
  algorithm (detection/NN-route/analysis) via standalone ports. NOT yet
  compiled with Maven/npm (no network here) — first real build is
  `docker compose up --build`.
- **v13 (2026-07-16)**: offline field mode. New backend `transfer/` package
  (MissionTransferService/Controller): mission export as self-contained ZIP +
  import with id-rewriting; StorageService.download(); multipart limits →
  4 GB. Frontend: Export bundle button (mission page), Import field bundle
  (dashboard), offline tile fallback + local tile-server override in
  map-draw. New root doc FIELD_DEPLOYMENT.md (router/LAN topology, office
  prep, offline dependency table incl. NTRIP→local RTK base, export/import
  workflow, field kit checklist).
- **v12.2 (2026-07-16)**: `skyscan_drone/lidar_bench.py` — standalone bench
  tester for the tilt+C1 assembly (live terminal dashboard, ASCII polar map,
  wedge bars, CSV dump). Tilt source selectable: `--encoder` (AS5600, gear-
  free) or `--no-encoder` (ESTIMATED via servo motion model, axis slew =
  servo_speed/gear). `--max-deg` and `--gear-ratio` selectable per rig and
  drive the pulse mapping + estimator. `--mock` desk mode; `--seconds` gives
  a scripted run with pass/fail exit code. Documented in SETUP_MANUAL Stage 6.
- **v12.1 (2026-07-16)**: added `legacy_drone_agent/SETUP_MANUAL.md` (staged setup/run/
  verification manual). Fixes: removed stale `vl53l1x` from requirements.txt
  (TFmini-S needs only pyserial/smbus2); removed `WatchdogSec=15` from the
  systemd unit (agent sends no sd_notify pings — systemd would kill it every
  15 s).
- **v12 (2026-07-16)**: added PROJECT_CONTEXT.md (this file) + root-README
  pointer and maintenance rule; documented Pixhawk 6C Pro TELEM2↔Jetson wiring.
- **v11 (2026-07-16)**: `lidar.plane: vertical|horizontal` switch across
  driver/assembler/agent/avoidance/self-test/config; OBSTACLE_DISTANCE ring
  restored for horizontal; forward TFmini optional+fused there, required for
  vertical. Both planes smoke-tested (digests, LAS, tilt geometry).
- **v10 (2026-07-16)**: reworked drone geometry from the WRONG horizontal-ring
  assumption to the real front-facing VERTICAL ring (matches backend
  FRONT_360_VERTICAL); per-orientation DISTANCE_SENSOR injection; forward
  TFmini-S added as required sensor; tangent-orbit rationale documented;
  ArduPilot-vs-PX4 decision section added (ArduPilot stays).
- Earlier: full stub-backed stack (state machine, planner, engine pipeline,
  viewer), Jetson agent with battery-leg resume, detour-and-scan avoidance,
  self-test framework, flight recorder.
