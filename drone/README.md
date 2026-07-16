# SkyScan drone companion — Jetson Orin Nano agent

Runs on the Jetson Orin Nano, speaks MAVLink to the **Pixhawk 6C Pro** and HTTP
to the SkyScan backend. Implements the real side of the `DroneConnector`
boundary: fully autonomous crude coverage flights and detailed structure
scans along SkyScan's planned routes, with battery-swap resume, layered
obstacle handling, georeferenced LAS output, and geotagged customer photos.

```
SkyScan backend ── JetsonDroneConnector (DRONE_CONNECTOR=jetson)
      ▲  poll /internal/drone/commands   ▼ POST events / scans / photos
┌─────┴──────────────────── Jetson Orin Nano ────────────────────────┐
│ agent.py — command loop · mission lifecycle · telemetry             │
│ route_executor — legs · battery swap · resume       mission_store   │
│ scan_assembler — lidar × pose × tilt -> LAS (UTM)    las_writer     │
│ safety/ — monitor (veto) · forward avoidance (BRAKE)                │
│ sensors/ — rplidar_c1 · tilt_servo(+AS5600) · tfmini_s · imx219      │
└───────┬───────────────┬──────────────┬───────────────┬─────────────┘
   MAVLink 921k     /dev/ttyUSB0    I2C (TCA9548A)    CSI (IMX219)
   Pixhawk 6C Pro   RPLidar C1     TFmini-S · AS5600 · PCA9685→DS3218
```

## How the two scan modes map to this hardware

There is **one** lidar — the C1, a 2D scanner mounted with its plane
**horizontal**: a 360° ring around the aircraft, tiltable ±30°. The tilt axis (DS3218 + AS5600 closed loop) *is* the
payload mode switch:

**Mounting decision (build-time): put the tilt on the ROLL axis** — the ring
tips left/right, not fore/aft. Rays on the tilt axis don't move when tilting,
so with roll mounting the fore/aft rays stay horizontal at any tilt angle:
**forward obstacle avoidance is tilt-immune** (verified in the assembler unit
test). Bonus: orbits are flown nose-forward along the tangent (efficient
flight, facade on the tilted side), and orbit direction selects which slice
faces the building — CW presents the up-tilted side, CCW the down-tilted side.

**Tilt is FIXED for the whole flight** (mechanical constraint). It is set on
the ground before takeoff, verified by the encoder, and carried as a mission
parameter (`tiltDeg`) chosen by the operator per flight — which pairs with the
rescan loop: fly a level pass first, then an upward-looking rescan of eaves,
then a downward-looking one of roofs, each as its own flight.

| SkyScan mode | Tilt (fixed per flight) | Pattern | 3D from |
|---|---|---|---|
| Crude / LANDSCAPE | −30° preset (side of the ring swathes terrain) | lawnmower | swath × forward motion |
| STRUCTURE detail | operator's choice: +30 / 0 / −30 | stacked orbits, nose-forward | ring × orbit levels; tilt selects the facade slice (level / up at eaves / down at roofs) |

Every lidar measurement is georeferenced with the **time-interpolated** vehicle
pose (GLOBAL_POSITION_INT + ATTITUDE at 10/20 Hz) and the **measured** encoder
tilt — never the commanded one. Output is LAS 1.2 in the mission CRS, exactly
what the SkyScan engine ingests.

## Multi-flight / battery swap

The route executor cuts the route into battery legs. At `rtl_pct` the drone
saves progress (atomically, to disk), RTLs, disarms and waits. After the swap,
the operator sends START/RESUME from the SkyScan console and the flight
continues **from the exact waypoint**, appending to the same scan. Progress and
partial scans survive agent restarts and Jetson power cycles. `land_now_pct`
is the hard floor beneath which the drone lands regardless.

## Object avoidance — layered and fully autonomous

No operator action at any stage:

1. **Flight controller layer** — the full 360° lidar ring streams to the FC as
   `OBSTACLE_DISTANCE`; with path planning enabled (`OA_TYPE=1` BendyRuler,
   `AVOID_ENABLE=7`, `PRX1_TYPE=2`) the FC curves GUIDED paths around
   obstacles on its own — most encounters never reach layer 2, and this works
   even if the companion process dies.
2. **Companion layer** — stop-and-hold with hysteresis: inside
   `stop_distance_m` → BRAKE + `OBSTACLE_STOP` event; auto-resume after 3 s
   continuously clear beyond `resume_clear_m`.
3. **Escalation: detour + scan.** A hold persisting past `detour_after_s`
   means a static, unexpected object. The companion locates it (nearest-return
   bearing/distance × pose), flies an arc around it at a safe radius **with
   capture running — so the obstacle itself is scanned into the deliverable**
   — exits on the far side and rejoins the planned route. Events:
   `OBSTACLE_DETOUR`, `OBSTACLE_SCAN_START/DONE`.
4. **Geometry layer** — routes stand off the structure; the geofence
   (mission polygon + margin) is checked onboard at every waypoint, detours
   included.

**Stated honestly:** the horizontal ring gives all-around coverage *at flight
altitude*; the blind axes are **up and down**. The downward TFmini-S covers
below; add an upward ToF before flying under canopies, eaves or wires.

## Hardware gap analysis — what the build is still missing

Must-have for fully automated flight:

1. **GNSS — the accuracy decision.** An M8N gets you *flying* (3D fix, AUTO
   missions, RTL) but not *surveying*: single-band, ~2.5 m CEP, no RTK, and
   0.5–1 m of EKF position wander that smears directly into the assembled
   cloud. Budget ladder (see "GNSS options" below): M8N now for airframe
   shake-down and crude passes → a **cheap RTK rover** (Quectel LC29H-class
   ~€40–60 or Unicore UM980-class ~€120–150) for centimetre pose → ZED-F9P
   only if you want the mature-ecosystem option. Whatever the receiver:
   antenna + compass on a ground-plane mast, away from ESC/power wiring, and
   NTRIP corrections over the LTE link.
2. **RC link + transmitter** — manual override is non-negotiable (and legally
   required almost everywhere). ELRS or similar, with failsafe configured.
3. **Telemetry/backhaul** — two different links with two different jobs. The
   SiK-class telemetry radio is the FC↔ground-station safety link (MAVLink to
   Mission Planner, works when everything else is down) — keep it. It is far
   too slow (~57–250 kbit/s) to carry scans, photos or logs, so the Jetson
   agent still needs an **LTE modem** (or on-site WiFi) as the data plane.
   **EU legality note:** 915 MHz is the US ISM band; in the EU (incl. Hungary)
   these radios must be the **868 MHz** variant — swap a 915 unit before
   flying, it is not a paperwork detail.
4. **Power infrastructure** — see the "Power tree" section below: PM02D for
   the FC + battery sensing, a dedicated regulated rail for the Jetson, and a
   strictly separate rail for the tilt servo. Leg planning is only as good as
   the fuel gauge — the PM02D's current sensing is what feeds it.
5. **Lateral + upward obstacle sensing** — the C1 covers forward only. Add
   4–6 **TF-Luna/TFmini-S** (8–12 m, I2C — your TCA9548A absorbs them) around
   the airframe plus one **upward**, feeding `OBSTACLE_DISTANCE`; or one
   depth camera (OAK-D/RealSense) forward-up if Jetson compute budget allows.
   The downward TFmini-S (12 m) is an altimeter/landing sensor, not avoidance.
6. **Remote ID module** — regulatory requirement (EU class marking / US Part 89).
7. **Anti-collision strobe** + FC buzzer/safety switch (6C Pro kit).

Strongly recommended:

8. **PPS time sync** — GNSS PPS wired to a Jetson GPIO disciplines the host
   clock; lidar↔pose sync error of even 10 ms at 4 m/s is 4 cm of smear.
9. **Parachute recovery** (e.g. dedicated ejection unit + crash detection) —
   flying close to buildings over people-adjacent ground, this is the
   difference between an incident and an accident.
10. **Prop guards or ducts** for standoff work; **landing gear** tall enough to
    protect the tilted lidar and camera on ground contact.
11. **NVMe SSD** on the Jetson for scan sessions; SD cards die.
12. **Vibration-isolated sensor mount** — lidar + camera + encoder on one rigid,
    damped plate; calibrate extrinsics (`lidar.mount` in config) *after* build.

## GNSS options, honestly ranked (4S budget build)

| Option | ~Cost | Pose quality | Verdict for scanning |
|---|---|---|---|
| **M8N (current)** | owned | 1.5–2.5 m abs, 0.5–1 m wander | Fine for autonomy, shake-down and crude/landscape passes; detail clouds will show metre-class smear and wall "ghosting" on orbits. Usable start, not the end state. |
| **Quectel LC29H(DA) rover** | ~€40–60 | cm with NTRIP | The budget RTK surprise: dual-band, RTK rover, tiny. Less battle-tested in ArduPilot than u-blox — bench-verify before trusting. |
| **Unicore UM980/UM982 board** | ~€120–150 | cm, triple-band | Excellent price/performance, good ArduPilot support, robust in urban multipath. **The sweet spot for this project.** |
| **u-blox ZED-F9P** | ~€200–260 | cm | The mature default; pay for ecosystem, not accuracy delta vs UM980. |

Corrections: NTRIP over the LTE link — in Hungary GNSSNET.hu (commercial) or a
community caster (rtk2go) with a nearby base; or a second cheap module as your
own base at the site. Partial mitigation without RTK: the engine's
reconstruction step (currently the SLAM stub) is exactly where scan-matching
would recover *relative* cloud consistency from M8N-grade pose — worth doing
eventually regardless of receiver.

## Power tree — 4S (14.8 V nominal, 16.8 V full, ~13.2 V floor)

One rail per consumer class, never share the servo's rail, star ground:

| Rail | Consumer | Board recommendation |
|---|---|---|
| Battery → FC | Pixhawk 6C Pro + its peripherals (TFmini-S, telemetry radio ≤0.5 A) | **Holybro PM02D** power module — powers the FC *and* provides the voltage/current sensing the battery leg-planner needs |
| Battery → 12 V/≥4 A | Jetson Orin Nano dev-kit barrel (9–19 V input) | **Pololu D36V50F12**-class buck (verify ≥4 A @ 12 V continuous). 12 V-in beats 5 V-in: half the current, cleaner under Orin power spikes. Battery-direct is *within* the 9–19 V window but unfiltered — don't. |
| Battery → 6 V/5 A | **DS3218 tilt servo only** | **HobbyWing UBEC 5 A** (or Matek equivalent) set to 6 V. Servo stall transients brown out anything sharing its rail — this isolation is the whole point. |
| Battery → 5 V/4–5 A | LTE modem, RPLidar C1 (if not USB-powered), fans, strobe | **Matek UBEC DUO** (dual output) or a second Pololu 5 V buck. LTE modems spike ~2 A on transmit — short, thick leads. |

Rules that save airframes: low-ESR 470–1000 µF electrolytic at every buck
input; ferrite or physical distance between switching regulators and the
GNSS/compass mast; fuse each rail; verify the whole tree under load with the
self-test's battery/FC checks before first flight; leave ≥30 % current
headroom per regulator.

## Key design principles for the airframe build

- **The pose is the product.** Every accuracy decision (RTK, PPS, rigid mount,
  vibration isolation, compass placement) feeds cloud quality directly. Spend
  weight and money here first.
- **Fail-operational for data, fail-safe for flight.** Scans persist per-leg;
  link loss never crashes a survey (events queue, flight continues); but any
  ambiguity about *flight safety* (EKF, battery, fence) resolves to RTL/LAND.
- **Two independent safety brains.** The FC's own failsafes (RC loss, battery
  LAND, crash detection, fence) must be configured to protect the aircraft even
  if the Jetson dies mid-flight. The companion only ever *adds* conservatism.
- **Energy margins are design inputs**: plan legs to 60 % of usable capacity,
  RTL at 30 %, hard land at 18 %; wind and cold eat the rest.
- **Nose-in orbits**: yaw follows the route tangent-to-target so the vertical
  scan plane faces the facade *and* the avoidance sensor faces the danger.
- **CG and mass budget**: the tilt assembly + Jetson + LTE + RTK add ~600–900 g
  forward; size motors/props for ≥2:1 thrust at MTOW *with* the payload, and
  keep the battery tray adjustable to trim CG per configuration.
- **EMI discipline**: GNSS/compass mast up and away; lidar USB and camera CSI
  short and shielded; power runs separated from signal runs.
- **Thermals**: Orin Nano under SLAM-adjacent loads wants airflow — duct
  prop-wash or fit a fan; throttling mid-scan corrupts timing.
- **Everything must fly in SITL first** (`sitl/run_sitl.sh`): the entire
  mission logic, resume, avoidance and upload path run against ArduPilot SITL
  with mocked sensors before a single real flight.
- **Calibrate, then trust the encoder**: servo commands lie under load; the
  AS5600 reading is authoritative and is what georeferencing consumes.

## Bring-up checklist

1. Flash ArduPilot Copter on the 6C Pro; set `SERIAL2_PROTOCOL=2`,
   `SERIAL2_BAUD=921`, `AVOID_ENABLE=7`, `PRX1_TYPE=2`, `RNGFND1_TYPE=10`
   (MAVLink), battery failsafes, fence.
2. Jetson: `pip install -r requirements.txt`, copy `config.yaml` →
   `/etc/skyscan/`, install `systemd/skyscan-drone.service`.
3. Backend: set `DRONE_CONNECTOR=jetson` — nothing else changes.
4. SITL end-to-end, then props-off bench test (tilt sweep, lidar stream,
   photo capture, telemetry in the console), then tethered hover, then the
   crude pass over an open field before any structure work.
