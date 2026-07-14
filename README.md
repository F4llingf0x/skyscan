# SkyScan — drone LiDAR point-cloud scanning service

A complete, locally runnable service for two-pass drone LiDAR surveys: an operator plans a
coarse scan, reviews a field preview, refines a detail zone, launches a detailed scan, and
the system processes the raw captures into a cloud-optimized point cloud delivered to the
customer through an embedded web viewer.

**No hardware required.** Everything that would touch the drone (Jetson Orin Nano / Pixhawk)
sits behind one interface backed by a simulated stub, so the entire lifecycle runs end-to-end
on a laptop.

## Run it

```bash
docker compose up --build
```

First build takes a few minutes (Maven, npm, CMake). Then:

| What | Where | Credentials |
|---|---|---|
| Web UI | http://localhost:4200 | `operator` / `password` (also `customer`, `admin`) |
| API + Swagger | http://localhost:8080/swagger-ui.html | JWT via `/api/auth/login` |
| MinIO console | http://localhost:9001 | `skyscan` / `skyscan-secret` |
| Engine health | http://localhost:9090/health | — |

A demo organization, three users, and a sample site (*Kőbánya Quarry — North Pit*) are seeded
on first boot; sample scans ship in `sample-data/`.

## Demo walkthrough (the full two-pass lifecycle)

1. Sign in as **operator** → open the seeded site → **Plan new mission**.
2. Draw a polygon on the map, set altitude / line spacing / speed → **Launch crude scan**.
3. Watch the live monitor: the flight-progress strip advances
   (`CRUDE_MISSION_UPLOADED → CRUDE_FLYING → CRUDE_LANDED`), the vehicle marker moves on the
   map, telemetry (alt/speed/battery/progress) streams over SSE. (~18 s simulated flight;
   **Abort** triggers `RETURN_TO_HOME → ABORTED`.)
4. At `PREVIEW_READY` the **field preview** appears. Draw the tighter detail polygon, set the
   detailed parameters (incl. terrain following) → **Launch detailed scan**.
5. After `DETAIL_LANDED` the backend stores both raw scans in MinIO and dispatches a job to
   the **C++ engine**, which cleans, downsamples, (stub-)georeferences, branches on
   `sensorType` (`FIXED_3D` vs `SPINNING_TILTED`), co-registers and merges the clouds, writes
   LAS + LAZ + COPC back to MinIO, and calls the backend back → mission `COMPLETE`.
6. Open the deliverable in the embedded viewer (orbit / pan / zoom / two-click distance
   measurement) or **Download LAZ**. Sign in as **customer** to see the customer portal:
   only their own sites and finished deliverables.

## Architecture

```
 Angular UI (nginx :4200)
   │  REST + SSE (proxied /api)
   ▼
 Spring backend (:8080) ── JWT auth · mission state machine · orchestration
   │           │                │
   │           │                └── DroneConnector  ◄─── THE stub/real boundary
   │           │                      └── StubDroneConnector (simulated flights + sample scans)
   │           ├── PostgreSQL (:5432)  domain model
   │           └── MinIO (:9000)       raw scans · previews · deliverables (presigned URLs)
   │  POST /jobs (async, presigned in/out URLs)
   ▼
 C++ engine (:9090) ── clean · downsample · georeference(STUB) · SLAM branch(STUB)
                       merge · LAS/LAZ/COPC → MinIO → callback /internal/jobs/{id}/callback
```

**Mission state machine** (`MissionStateMachine`, hand-rolled explicit transition table):
`IDLE → AREA_SELECTED → CRUDE_MISSION_UPLOADED → CRUDE_FLYING → CRUDE_LANDED → PREVIEW_READY
→ DETAIL_SELECTED → DETAIL_MISSION_UPLOADED → DETAIL_FLYING → DETAIL_LANDED → PROCESSING →
COMPLETE`, plus `RETURN_TO_HOME → ABORTED` and `FAILED`. All transitions are guarded; the
orchestrator (`MissionService`) is the single writer and the source of truth the UI follows
over SSE.

**Backend ↔ engine contract:** the backend POSTs `{jobId, inputs[{sensorType, passType,
getUrl}], outputs{lasPutUrl, lazPutUrl, copcPutUrl}, targetCrs, callbackUrl}` to the engine,
which replies `202`, processes on a worker thread, uploads via the presigned PUTs, and POSTs
`{status, pointCount, bounds, crs, message}` to the callback with the `X-Internal-Token`
shared secret. The engine holds no storage credentials.

## Stub boundaries (and how to replace them)

Every hardware-facing or heavy-geometry placeholder is marked with `STUB` in code.

| Stub | Where | Real replacement |
|---|---|---|
| Drone / companion computer | `backend …/connector/stub/StubDroneConnector.java` | Implement `DroneConnector` with MAVLink/ROS transport; register the bean and set `DRONE_CONNECTOR` (property `skyscan.connector.impl`) to select it. **Nothing else changes** — the orchestrator only sees the interface. |
| Coverage path planning | inside the stub's simulated flight | Real CPP on the companion computer or backend |
| GNSS/IMU metadata | fabricated JSON in the stub | Real trajectory/ephemeris from the vehicle |
| Georeferencing | `engine/src/pipeline.cpp` `georeference()` | PDAL `filters.reprojection` + trajectory/boresight solution |
| SLAM reconstruction (`SPINNING_TILTED`) | `reconstructSpinningTilted()` | Real SLAM / continuous-time optimization; the branch and I/O are already real |
| Field preview generation | bundled sample PNG delivered by the stub | Quick downsample/rasterize on landing |
| LAZ/COPC conversion | `convertWithPdal()` | Already real when `pdal` is present (it is, in the Docker image); falls back to a marked byte-copy without it |
| Potree/COPC streaming | `frontend …/viewer/potree-loader.ts` | The wrapper loads Potree from a CDN and opens the COPC stream; on any failure the built-in three.js viewer (orbit/pan/zoom/measure over the processed LAS) takes over so the demo always works |

What is **real** today: the whole mission lifecycle and state machine, auth + per-customer
scoping, async job orchestration and callbacks, object storage with presigned range-capable
URLs, SSE live telemetry, LAS parsing/writing, outlier removal (MAD-based), voxel
downsampling, overlap-based co-registration and merging, and the sensor-type processing
branch.

## Repository layout

```
backend/    Spring Boot 3 (Java 17): API, JWT auth, state machine, orchestration,
            MinIO integration, job dispatch, SSE, stub connector, seeder
engine/     C++17 worker: HTTP job intake, libcurl transfers, LAS pipeline, PDAL
            conversion, CLI mode (skyscan-engine --cli crude.las detailed.las out/)
frontend/   Angular 17: login, dashboard, map planner (Leaflet + draw), live monitor,
            preview & refine, deliverables, embedded viewer, customer portal mode
sample-data/  bundled sample LAS scans (crude FIXED_3D + detailed SPINNING_TILTED)
              and the field-preview image used by the stub
```

## Configuration

Everything is environment-driven (see `docker-compose.yml` / `application.yml`):
connector selection (`DRONE_CONNECTOR`), storage endpoints (internal vs browser-facing for
presigning), target CRS (`TARGET_CRS`), engine URL and callback base, JWT secret, and the
`INTERNAL_TOKEN` shared secret guarding `/internal/**`.

## Development without Docker

- **Backend:** `cd backend && mvn spring-boot:run` (needs local Postgres + MinIO).
- **Engine:** `cd engine && ./get-deps.sh && cmake -B build && cmake --build build`,
  then `./build/skyscan-engine` (service) or `--cli` (offline pipeline run).
- **Frontend:** `cd frontend && npm install && npm start` (proxies `/api` to `:8080`).

## Notes for reviewers

- API is documented via OpenAPI at `/swagger-ui.html`.
- Health: backend `/actuator/health`, engine `/health`.
- Structured logs: mission transitions (`Mission {}: A -> B`), `[pipeline]` stage lines in
  the engine, `STUB connector:` lines for simulated hardware events.
- Demo-grade settings to change before any real deployment are marked in config
  (JWT secret, CORS `*`, internal token).
