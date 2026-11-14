# SkyScan drone — setup, run & verification manual

Step-by-step bring-up of the Jetson Orin Nano companion + Pixhawk 6C Pro,
from bare boards to a verified autonomous survey. Follow the stages **in
order** — each stage's checks are the entry condition for the next. Details of
*why* things are the way they are live in `README.md`; this file is the *do
this, then verify that* companion.

Stages: 0 prerequisites → 1 Pixhawk → 2 Jetson → 3 wiring → 4 config →
5 SITL (no hardware) → 6 bench (props OFF) → 7 calibration → 8 first flights →
9 field operations → 10 troubleshooting.

---

## Stage 0 — Prerequisites

Hardware in hand (see README "Hardware gap analysis" for the full rationale):

- Pixhawk 6C Pro + SD card, Holybro PM02D power module
- Jetson Orin Nano dev kit + NVMe SSD, 12 V ≥4 A buck from the 4S battery
- RPLidar C1 (USB), tilt assembly: DS3218 servo + AS5600 encoder + PCA9685,
  TCA9548A I2C mux, 6 V ≥5 A UBEC **dedicated to the servo**
- 2× TFmini-S (down = UART, forward = I2C behind the mux)
- IMX219 CSI camera
- GNSS receiver + compass on a mast (M8N to start, RTK for survey quality)
- RC transmitter + receiver with failsafe (EU: 868 MHz ELRS/SiK — not 915!)
- SiK telemetry radio pair (ground station link), LTE modem or site WiFi
- A ground station laptop with Mission Planner (or MAVProxy/QGC)
- The SkyScan backend reachable from the Jetson (LAN, VPN or LTE)

## Stage 1 — Pixhawk 6C Pro (flight controller)

1. Flash **ArduPilot Copter** (latest stable) with Mission Planner over USB.
2. Mandatory parameters (Config → Full Parameter List):

   | Param | Value | Why |
   |---|---|---|
   | `SERIAL2_PROTOCOL` | 2 | MAVLink 2 on TELEM2 (the Jetson port) |
   | `SERIAL2_BAUD` | 921 | 921600 baud, matches `config.yaml` |
   | `BRD_SER2_RTSCTS` | 0 | no flow-control wires |
   | `AVOID_ENABLE` | 7 | use proximity + fence + beacons |
   | `PRX1_TYPE` | 2 | MAVLink proximity (consumes our DISTANCE_SENSOR stream) |
   | `RNGFND1_TYPE` | 10 | MAVLink rangefinder (downward TFmini-S via the agent) |
   | `RNGFND1_ORIENT` | 25 | downward |
   | `OA_TYPE` | 1 only if `lidar.plane: horizontal` | BendyRuler needs the full ring; leave 0 for the vertical mount |
   | `FENCE_ENABLE` | 1, `FENCE_TYPE=7` | altitude + circle + polygon |
   | `BATT_MONITOR` | per PM02D docs | leg planning reads FC battery % |
   | `BATT_FS_LOW_ACT` | 2 (RTL) | FC-side battery failsafe |
   | `BATT_FS_CRT_ACT` | 1 (LAND) | hard floor, independent of the agent |
   | `FS_THR_ENABLE` | 1 | RC loss failsafe → RTL |
   | `WP_YAW_BEHAVIOR` | 1 (default) | nose points at the next waypoint — required by the tangent-orbit scanning geometry |

3. Calibrations in Mission Planner: accelerometer, compass (outside, away
   from the bench), radio, ESC, battery monitor (PM02D voltage/current).
4. Verify: `STATUSTEXT` shows no `PreArm:` errors with GNSS outdoors.

## Stage 2 — Jetson Orin Nano (companion)

```bash
# 1. OS: JetPack 6.x on NVMe. Then:
sudo systemctl stop nvgetty && sudo systemctl disable nvgetty   # free /dev/ttyTHS1
sudo usermod -aG dialout,i2c $USER && sudo reboot

# 2. Project + dependencies
sudo mkdir -p /opt/skyscan && sudo chown $USER /opt/skyscan
# copy the drone/ folder to /opt/skyscan/drone, then:
cd /opt/skyscan/drone && pip3 install -r requirements.txt

# 3. Runtime directories (agent writes here; systemd runs as user "skyscan")
sudo useradd -r -G dialout,i2c skyscan 2>/dev/null || true
sudo mkdir -p /var/lib/skyscan/{scans,photos,logs} /etc/skyscan
sudo chown -R skyscan /var/lib/skyscan

# 4. Config + service
sudo cp config.yaml /etc/skyscan/config.yaml     # then EDIT IT — Stage 4
sudo cp systemd/skyscan-drone.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable skyscan-drone
# do NOT start it yet — wiring and config first
```

I2C sanity for the sensor tree (bus 7 on the Orin Nano 40-pin header):

```bash
sudo apt install -y i2c-tools
i2cdetect -y 7        # expect 0x70 (TCA9548A) and 0x40 (PCA9685)
```

## Stage 3 — Wiring (power OFF while connecting)

| Link | From | To |
|---|---|---|
| MAVLink | Pixhawk **TELEM2** JST-GH: pin2 TX, pin3 RX, pin6 GND (pin1 5V **unconnected**) | Jetson 40-pin: pin10 RX, pin8 TX, pin6 GND (`/dev/ttyTHS1`) |
| Lidar | RPLidar C1 USB adapter | Jetson USB (`/dev/ttyUSB0`) — stable 5 V! |
| TFmini-S down | UART, factory 115200 | Jetson `/dev/ttyTHS2` (pins 11/12 = UART2) |
| TFmini-S forward | switched to I2C mode, addr 0x10 | TCA9548A **channel 1** |
| AS5600 tilt encoder | I2C 0x36 | TCA9548A **channel 2** |
| PCA9685 → DS3218 | PWM ch 0 | servo signal; servo power from the **dedicated 6 V UBEC** |
| Camera | IMX219 | Jetson CSI |
| GNSS+compass | mast | Pixhawk GPS1 |
| RC receiver | — | Pixhawk SBUS (or ELRS on a spare UART) |
| SiK radio | — | Pixhawk TELEM1 |
| Power | battery → PM02D → Pixhawk POWER1; battery → 12 V buck → Jetson; battery → 6 V UBEC → servo | see README "Power tree" — never share the servo rail |

Verify the MAVLink link before anything else:

```bash
pip3 install mavproxy
mavproxy.py --master=/dev/ttyTHS1 --baud=921600
# expect "heartbeat from system 1" within ~2 s; type `param show SERIAL2_BAUD`
```

## Stage 4 — Configuration (`/etc/skyscan/config.yaml`)

Set, in this order of importance:

1. `drone_id` — unique per airframe.
2. `backend.base_url` + `backend.internal_token` — the token must equal the
   backend's internal token (backend `application.yml` / env). Backend must
   run with `DRONE_CONNECTOR=jetson`.
3. `lidar.plane` — `vertical` (nose mount, survey-optimal, **forward TFmini-S
   required**) or `horizontal` (center mount, avoidance-optimal). This single
   switch drives the assembler geometry, obstacle digests, FC injection and
   self-test severity.
4. `lidar.mount.offset_m` — measure sensor origin from the FC's IMU after the
   physical build: x forward, y right, z down, in metres.
5. `lidar.angle_zero_deg` — Stage 7 calibration (UP for vertical, NOSE for
   horizontal).
6. `tilt.*` — verify `i2c_bus`, `encoder_mux_channel`, `pca9685_channel`
   match the wiring; `zero_offset_deg` from Stage 7.
7. `rangefinder.down` port; `rangefinder.forward.enabled: true` + its
   `mux_channel` (vertical mount: mandatory).
8. `flight.battery` thresholds — leave the conservative defaults
   (90 / 30 / 18 %) until you have measured real endurance.
9. `avoidance.*` — defaults are sane for first flights; do not loosen
   `stop_distance_m` below 4 m until Stage 8 is complete.
10. `output.target_crs` — note the assembler currently hardcodes UTM 34N
    (Hungary); changing CRS needs a code change (known gap).

## Stage 5 — SITL: fly everything with zero hardware

Run this before ANY real bench work, and again after every code change.

```bash
# terminal 1 — ArduPilot SITL at your site coordinates:
sim_vehicle.py -v ArduCopter -L 47.49,19.05,120,0 --out=udp:127.0.0.1:14550
# terminal 2 — the agent with mocked sensors:
cd /opt/skyscan/drone
SKYSCAN_MOCK_SENSORS=1 python3 -m skyscan_drone.agent --config config.yaml
#   (config: mavlink.device: udp:127.0.0.1:14550, backend.base_url -> your dev backend)
```

**Verify the full lifecycle from the SkyScan UI** (operator login):

- [ ] Plan a mission, launch crude → agent events in the live monitor:
      `SELFTEST_REPORT` → `PREFLIGHT_PASS` → `LEG_START` → `TELEMETRY` stream
      → `TILT_SET` → waypoints advance → `ROUTE_COMPLETE` → LAS upload,
      mission reaches `PREVIEW_READY`.
- [ ] Detail pass to `COMPLETE`, deliverable opens in the viewer.
- [ ] **Battery swap**: set `rtl_pct: 95` temporarily → expect `BATTERY_SWAP`,
      RTL, disarm; press RESUME in the UI → `RESUMING` at the exact waypoint;
      final LAS contains both legs.
- [ ] **Resume across restart**: kill the agent mid-mission, restart it,
      RESUME → continues from `/var/lib/skyscan/mission_progress.json`.
- [ ] **Abort**: `ABORTED` state, RTL in SITL map.
- [ ] **Obstacle**: the mock lidar/TFmini synthesize an obstruction — expect
      `OBSTACLE_STOP` → (persisting) `OBSTACLE_DETOUR` →
      `OBSTACLE_SCAN_START/DONE` → route rejoin.

## Stage 6 — Bench verification, PROPS OFF

Battery connected, propellers removed. Start the agent
(`sudo systemctl start skyscan-drone`; logs: `journalctl -u skyscan-drone -f`).

**The self-test is the checklist.** A `full` self-test runs on mission upload
and its report lands in the UI (`SELFTEST_REPORT` event) and in the flight
log. Every component below must PASS — the agent refuses takeoff on any FAIL
(`PREFLIGHT_FAIL` names the component and the reason):

| Check | PASS means | If not |
|---|---|---|
| `backend_link` | command poll round-trip < 5 s | check URL/token, LTE/WiFi |
| `fc_link` | fresh pose from MAVLink (< 1 s old) | Stage 3 wiring, SERIAL2 params, nvgetty |
| `fc.sensors.*` | FC's own health bitmask clean | resolve in Mission Planner first |
| `gps` | 3D fix, ≥ 8 sats (RTK reported when fixed) | mast placement, sky view |
| `ekf` | EKF pos/vel healthy | wait after boot; compass cal |
| `battery` | telemetry present, ≥ `leg_start_min_pct` | PM02D wiring, BATT_MONITOR |
| `fc.messages` | no recent FC errors ≥ WARNING | read the verbatim text in the report |
| `lidar` | scans streaming ≥ 60 % of `scan_hz`, fresh | USB power, port, motor spins |
| `rangefinder` | down TFmini fresh, 0.1–12 m plausible | port/baud; sunlight note |
| `rangefinder_fwd` | forward TFmini healthy (vertical mount: FAIL if absent/silent) | mux channel 1, I2C mode, 5 V |
| `storage` | > 5 GB free in `output.scan_dir` | offload old sessions |
| `tilt` (full mode) | sweep min→0→max, encoder within `settle_tolerance_deg` | linkage, 6 V rail, mux ch 2 |
| `camera` (full mode) | test JPEG captured, plausible size | CSI seat, nvargus daemon |
| `vision` | active CV backend (mock or tensorrt engines loaded) | for crop missions: build engines or set backend=mock |

**Isolated tilt-lidar bench tool** — test the tilt assembly + C1 on their own,
before (or instead of) running the whole agent:

```bash
python3 -m skyscan_drone.lidar_bench --encoder                 # AS5600-verified tilt
python3 -m skyscan_drone.lidar_bench --no-encoder \
        --gear-ratio 2.5 --max-deg 20                          # pre-encoder rig: tilt ESTIMATED
python3 -m skyscan_drone.lidar_bench --mock --sweep            # desk demo, no hardware
python3 -m skyscan_drone.lidar_bench --seconds 10 --tilt 15    # scripted pass/fail (exit code)
```

Live terminal dashboard (SSH-friendly): scan rate, points/rev, wedge minima
bars, nearest return, an ASCII polar map of the ring, and the tilt readout
labelled with its source — ENCODER (AS5600) or ESTIMATED (servo motion model:
axis slew = servo speed / gear ratio). `--max-deg` and `--gear-ratio` are
selectable per rig and drive both the command pulse mapping and the estimate;
the encoder reading itself is gear-free (it sits on the axis). Keys: ±/arrows
tilt, 0 center, s sweep, e encoder↔estimate, w dump a revolution to CSV
(/tmp/lidar_bench_*.csv, tilt stamped per row), q quit. Use it for the Stage 7
`angle_zero_deg` and tilt-zero calibrations: the wedge bars and polar map make
"which way is up" mistakes obvious in seconds.

**Camera bench tool** — test the IMX219-77 module on its own:

```bash
python3 -m skyscan_drone.camera_bench --count 5                 # capture path + file sanity
python3 -m skyscan_drone.camera_bench --count 10 --interval 2.5 # mission-cadence check
python3 -m skyscan_drone.camera_bench --mock                    # desk demo, exit-code scripted
```

Verifies the nvargus→JPEG pipeline, per-shot latency, JPEG magic + actual
resolution vs requested, EXIF geotag round-trip, and whether capture keeps up
with the mission photo interval. It also prints the ground footprint + GSD at
1.5 / 6 / 40 m for the configured FOV (64.9°×51.0°, derived from the module's
77° diagonal). **Lens-variant check**: at 1.5 m above a floor target the frame
must span ≈1.9×1.4 m for the 77° module — tape-measure it; ≈1.45×1.1 m means
the stock 62.2° variant. Set the matching id in `camera.module` (catalog:
imx219-std / imx219-77 / imx219-120 / imx219-160) — the operator can also
override it per mission in the planner, and the mission's choice wins on the
drone. A wrong module misplaces every detected plant (~1.3 m at the frame
edge from 40 m between the std and 77° variants). Re-run this bench with
`--module <id>` after any lens change.

**Full recognition + health-evaluation bench** — the whole CV chain
(detect → project/dedup → classify → mini report) with no flight and no agent:

```bash
python3 -m skyscan_drone.vision_bench --source mock --frames 30 --min-hits 2   # zero hardware
python3 -m skyscan_drone.vision_bench --source camera --frames 12              # camera + models
python3 -m skyscan_drone.vision_bench --source dir --dir ./field_frames \
        --detector tensorrt --classifier tensorrt                              # engine acceptance
python3 -m skyscan_drone.vision_bench --task animals --frames 20 --mock        # wildlife chain
```

`--task animals` exercises the WILDLIFE_SURVEY chain (RGB detector + thermal
hotspots → fusion → moving-target dedup → time/location tags). The thermal
camera (FLIR Lepton 3.5 on a PureThermal UVC board at `thermal.device`) mocks
automatically in SITL; its real UVC capture is a marked TODO in
sensors/thermal.py until the board is on the bench.

Prints per-stage counts and latencies, a severity-sorted findings table, and
writes JSON to /tmp. `--source dir` over real field frames with the tensorrt
backends is the acceptance test for trained engines before flying them. Exit
code is scripted-friendly (0 = ≥1 plant found and every plant classified).

**Hover & dodge test (props ON — read all of this first).** From the console's
Drone check page (staff): the drone arms, rises to the set height (default
1 m, hard-capped at 3 m) on the downward rangefinder, holds for the set
duration, and dodges obstacles that move TOWARD it — walk at it slowly from a
covered side and it tilts away; step inside 0.6 m and it lands immediately
instead of dodging. **GPS is not used or required** (GUIDED_NOGPS +
attitude/thrust): at 1 m for a minute, drift is irrelevant — attitude comes
from the IMU, height from the rangefinder, proximity from the lidar, so this
works indoors.

Safety, non-negotiable: netted or fully clear area; prop guards strongly
recommended; one person, slow approaches only; keep the Abort button in reach
(controlled landing); the drone auto-lands on stale rangefinder, battery
floor, or an obstacle inside the hard minimum. Sensor coverage depends on the
mount — **vertical ring: front + sides only, the rear is blind**; horizontal
ring: all directions. Tune `hover_test.hover_thrust` to your airframe's hover
point in SITL first (SIM_* frame differs from your real weight).

Manual spot-checks on top of the self-test:

- [ ] **Lidar geometry**: hold a board ~2 m from the sensor in a known
      direction; in the flight-recorder JSONL (`/var/lib/skyscan/logs`,
      10 Hz state) confirm the matching wedge (`ringMinM.up/down/left/right`,
      or `fwdMinM`) drops to ~2 m. This validates `angle_zero_deg`.
- [ ] **Tilt encoder truth**: command ±30° from a test mission's `tiltDeg`;
      `TILT_SET` event must report `measuredDeg` within tolerance — the
      measured value is what georeferencing uses.
- [ ] **FC sees the sensors**: Mission Planner → Status: `rangefinder1`
      shows the down TFmini; Proximity view (Ctrl-F → Proximity) shows the
      injected DISTANCE_SENSOR directions (and the full ring if horizontal).
- [ ] **Photos**: a capture appears under `/var/lib/skyscan/photos/<mission>`
      with GPS EXIF once the FC has a fix.
- [ ] **Watchdogs**: unplug the lidar USB mid-run — agent logs
      `lidar restart after error`, self-test flips to FAIL, and re-plugging
      recovers without an agent restart.

## Stage 7 — Calibration (do once per build, re-do after any mount change)

1. **Tilt zero** (`tilt.zero_offset_deg`): set the ring optically
   level/vertical with a spirit level or jig, read the raw encoder
   (`TiltServo.read_angle_deg` with offset 0 — a one-liner in a Python REPL),
   write the offset.
2. **Lidar angle zero** (`lidar.angle_zero_deg`): with the drone level, place
   a target straight UP above the sensor (vertical mount) or dead ahead of
   the NOSE (horizontal); adjust until the nearest-return angle reads 0°.
3. **Mount extrinsics** (`lidar.mount.offset_m`): tape-measure sensor origin
   relative to the FC (x fwd, y right, z down). Centimetres matter — they map
   1:1 into the cloud.
4. **Static scan sanity**: props off, drone level and stationary with GNSS
   fix, run a 30 s capture (start/abort a bench mission), open the LAS in the
   SkyScan viewer or CloudCompare: floor must be a level plane, a wall must
   be vertical, ranges true. Tilt the airframe by hand — the scene must NOT
   move (attitude compensation working).
5. FC-side: accel/compass/RC/battery calibrations from Stage 1 verified
   outdoors; `PreArm` clean.

## Stage 8 — First flights (in this exact order)

Open area, away from people; RC pilot ready to take over at every step.

1. **Manual hover** (STABILIZE/ALTHOLD, agent stopped): trims, vibrations
   (Mission Planner vibe < 30), battery sag sanity.
2. **GUIDED smoke test** (agent running, no mission): from Mission Planner,
   arm + takeoff + a couple of "fly to here" clicks — proves GUIDED behaves
   before the agent commands it.
3. **Tethered/low crude pass**: small polygon, `takeoff_alt_m: 5`, watch
   `OBSTACLE_*` stays quiet in open space, LAS lands in the UI.
4. **Full crude pass** over open terrain; inspect the cloud: line spacing
   consistent, swath width as predicted (~2·√(range²−AGL²) for the vertical
   mount), no gaps between lawnmower lines.
5. **Avoidance rehearsal**: place a large soft obstacle (cardboard stack) on
   the route → expect `OBSTACLE_STOP`, then `OBSTACLE_DETOUR` + arc + scan +
   rejoin. Do NOT skip this before structure work.
6. **Battery-swap rehearsal**: long route, real `rtl_pct`; swap pack; RESUME;
   verify seam-free cloud.
7. **First structure scan**: simple building, `standoffM ≥ 8`, tilt 0; then
   the ±30° rescan flights. Verify facade completeness floor-to-roof and that
   orbit levels stitch without vertical banding.

## Stage 9 — Field operations (routine)

- Power on FC + Jetson; agent auto-starts (systemd) and begins polling.
- Operator: UI → site → plan → launch. The agent runs a `full` self-test on
  upload and a `quick` one before EVERY leg — takeoff is blocked on FAIL and
  the console shows the exact failing component (`PREFLIGHT_FAIL`).
- During flight, watch the live monitor: `TELEMETRY` (1 Hz), `fwdMinM` /
  `ringMinM` in logs, `FC_MESSAGE` events surface any FC warning verbatim.
- Battery swap: land on `BATTERY_SWAP`, swap pack, wait for GNSS + EKF, press
  RESUME. Progress and partial scans survive power-cycling the Jetson.
- After landing: LAS + photos upload automatically (`LANDED` with scan);
  flight log JSONL uploads for postmortems.
- Abort at any time from the UI (`ABORT` → RTL) or take over on RC — the RC
  link and FC failsafes function fully independently of the agent.

## Stage 10 — Troubleshooting quick table

| Symptom | First suspects |
|---|---|
| `fc_link` FAIL / no heartbeat | nvgetty re-enabled after JetPack update; TX/RX not crossed; `SERIAL2_*` params; wrong `mavlink.device` |
| Agent connects on USB but not TELEM2 | `BRD_SER2_RTSCTS` not 0; baud mismatch |
| `lidar` restart loop | USB 5 V sag (power the C1 from a good rail), cable, `/dev/ttyUSB0` claimed by another process |
| Wedges/sectors look rotated | `angle_zero_deg` wrong or plane mismatch between config and the physical mount |
| `tilt` never settles | servo rail shared/undersized (must be the dedicated 6 V UBEC), linkage slop, wrong `encoder_mux_channel` |
| `rangefinder_fwd` FAIL | TFmini still in UART mode (must be switched to I2C for the mux), wrong channel, addr ≠ 0x10 |
| Cloud smeared / walls doubled | GNSS grade (see README GNSS ladder — RTK), vibration on the sensor plate, wrong `mount.offset_m`, clock sync (PPS) |
| Scene moves when airframe tilts (bench) | attitude compensation broken: check ATTITUDE stream rate and `pose_at` interpolation in logs |
| Scan empty though flight ran | no EKF position at capture start (assembler drops points until first fix) — check `gps`/`ekf` self-test order of events |
| Backend 401/403 on poll | `internal_token` mismatch with the backend |
| Agent dies periodically under systemd | stale unit file — ensure the shipped one (no `WatchdogSec`) is installed |

---

*Keep this manual and `PROJECT_CONTEXT.md` in sync with code changes — the
self-test table in Stage 6 mirrors `selftest.py` component names verbatim.*
