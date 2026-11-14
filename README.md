# SkyScan

**An autonomous drone that scans buildings and terrain with LiDAR, plus the software that turns those scans into finished point clouds.**

I'm building all of it myself: the web platform, the processing engine, the onboard flight software, and the drone. I'm a software developer and a mechanical engineer. I design and build the airframe, sensor mounts and power system, and I write the code that flies it and processes what it collects.

```
 Operator (browser)            Ground station                         Drone
┌──────────────────┐   ┌────────────────────────────┐   ┌─────────────────────────────┐
│ Angular 17 UI    │──▶│ Spring Boot 3 backend      │◀─▶│ Jetson Orin Nano agent      │
│ map planner,     │SSE│ mission state machine,     │   │ (Python, MAVLink)           │
│ live monitor,    │◀──│ JWT auth, orchestration    │   │ route executor, safety veto,│
│ 3D viewer        │   │ PostgreSQL · MinIO         │   │ avoidance, LAS assembly     │
└──────────────────┘   │            │               │   └──────────────┬──────────────┘
                       │            ▼               │                  │ MAVLink
                       │ C++17 point-cloud engine   │   Pixhawk 6C · RPLidar C1 on a
                       │ clean · downsample · merge │   tilt servo · TFmini-S · IMX219
                       │ LAS / LAZ / COPC           │   self-built airframe, 4S power
                       └────────────────────────────┘
```

## What it does

1. The operator draws a zone on a map. The drone flies an autonomous **crude scan** and sends back a quick preview.
2. The operator refines a detail zone. The system plans a **detailed scan** and the drone flies it. It avoids unexpected obstacles and scans them too.
3. The C++ engine cleans, downsamples, co-registers and merges the raw clouds. It delivers **LAS, LAZ and COPC** files, which the customer views in an embedded 3D viewer.

You don't need any hardware to try it. The drone sits behind one `DroneConnector` interface. A simulated connector runs the whole lifecycle on a laptop, and the real Jetson agent plugs into the same interface.

```bash
cd ground-station && docker compose up --build    # then open http://localhost:4200
```

## Repository

| Folder | What's inside |
|---|---|
| [`ground-station/`](ground-station/) | Operator and customer web UI (Angular 17), backend (Spring Boot 3, Java 17, PostgreSQL, MinIO), C++17 processing engine (PDAL, LAS/LAZ/COPC), sample data. [Full README](ground-station/README.md) |
| [`aerial-building-scanner/`](aerial-building-scanner/) | The drone side. [`legacy_drone_agent/`](aerial-building-scanner/legacy_drone_agent/) is the working Python flight agent: MAVLink control, tilt-LiDAR scan assembly, layered safety, battery-swap resume, sensor bench tools and SITL. Next to it is the planned **ROS 2 rewrite**: a digital twin (PX4 SITL + Gazebo), view planning, state estimation and evaluation against ground truth. |

## Engineering highlights

- **Explicit mission state machine.** Every transition is guarded, and one orchestrator is the only writer. The UI follows the state live over SSE.
- **One clean hardware boundary.** Simulation and the real drone use the same `DroneConnector` interface, so the backend never knows which one it's talking to.
- **Asynchronous processing contract.** The backend gives the engine presigned input and output URLs plus a callback. The engine never holds storage credentials.
- **Real onboard autonomy.** The flight agent includes route legs with battery-swap resume, a safety monitor that can veto commands, forward obstacle braking, and georeferenced LAS output from a servo-tilted 2D LiDAR.
- **Hardware design decisions are written down.** The [drone README](aerial-building-scanner/legacy_drone_agent/README.md) covers LiDAR mount planes, ArduPilot vs PX4, a GNSS options ranking, the 4S power tree and a bring-up checklist.
- **Placeholders are clearly marked.** Every unfinished piece is tagged `STUB` in the code and listed with its planned replacement, so it's clear what's real and what isn't.

## Status and what's next

| | |
|---|---|
| ✅ Working | Full mission lifecycle in simulation, web platform, C++ processing pipeline, Jetson flight agent (SITL and sensor benches) |
| 🔧 In progress | Airframe build and hardware bring-up |
| 🧭 Next | Aerial Building Scanner: a simulation-first ROS 2 stack that produces complete, colored building point clouds, scored against digital-twin ground truth. See the [roadmap](aerial-building-scanner/docs/roadmap.md). |
| ⚠️ Known gap | No automated test suite yet. Verification is SITL plus smoke probes. Unit, scenario and bag-replay tests are built into the new stack's plan. |

**Stack:** Java 17 · Spring Boot 3 · Angular 17 · TypeScript · C++17 · PDAL · Python · MAVLink · PostgreSQL · MinIO · Docker · ROS 2 / PX4 / Gazebo (planned)
