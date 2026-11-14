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

## Mount plane: `lidar.plane` — one switch, two airframes

The same C1 can be built into the airframe two ways; everything downstream
(assembler geometry, obstacle digests, FC injection, self-test severity)
follows the single config switch `lidar.plane`:

| | `vertical` (default) | `horizontal` |
|---|---|---|
| Mount | nose, spin axis along body X | center, spin axis vertical |
| Ring covers | up / right / down / left | full 360° at flight altitude |
| Tilt (±30° about body Y) | + leans ring TOP toward the nose | + lifts the NOSE ray |
| Scanning | pushbroom: facade floor-to-roof per sweep, wide terrain swath — few orbit levels/lines | narrow band at flight altitude — several× more levels/lines for the same coverage |
| Avoidance | blind fore/aft → **forward TFmini-S REQUIRED** (self-test FAILs without it); sides/up from the ring | full horizontal ring → `OBSTACLE_DISTANCE` + BendyRuler; blind up/down; forward TFmini-S optional redundancy (fused via min) |
| FC params | `PRX1_TYPE=2` (per-orientation DISTANCE_SENSOR) | `PRX1_TYPE=2` + optionally `OA_TYPE=1` (BendyRuler) |
| Pick it when | the point cloud is the product (survey work) | cluttered sites where avoidance robustness beats survey speed |

In both mounts the lateral rays lie on the tilt axis, so side ranging is
tilt-immune. Switching planes is a config + rebuild-of-the-mount affair — no
code changes; re-measure `lidar.mount.offset_m` and recalibrate
`angle_zero_deg` (points UP in vertical, at the NOSE in horizontal).

## How the two scan modes map to the DEFAULT (vertical) mount

There is **one** lidar — the C1, a 2D scanner mounted at the NOSE with its
spin axis along the body X axis: the 360° scan plane is **VERTICAL** (body
Y-Z), covering up / right / down / left around the aircraft. The whole sensor
pitches ±30° about the lateral axis (DS3218 + AS5600 closed loop) — positive
tilt leans the **top of the ring toward the nose**. 3D comes from motion: the
vertical fan is a pushbroom that the flight path sweeps through the scene.

Two geometric facts drive everything downstream:

1. **The lateral rays (±90°) lie ON the tilt axis**, so they stay exactly
   horizontal at any tilt: side ranging and facade returns are tilt-immune
   (verified in the assembler unit test).
2. **The ring is blind along the nose and tail.** Forward obstacle sensing
   comes from a dedicated forward TFmini-S (`rangefinder.forward`) — this is
   a REQUIRED sensor, not an option. Tilt only buys steep look-ahead: +30°
   sees up-and-forward (~60° elevation, wires/eaves above the path), −30°
   sees down-and-forward (terrain rising into the path).

**Tilt is FIXED for the whole flight** (mechanical constraint). It is set on
the ground before takeoff, verified by the encoder, and carried as a mission
parameter (`tiltDeg`) chosen by the operator per flight — which pairs with the
rescan loop: fly a vertical-ring pass first, then a +30° rescan of eaves and
soffits from below, then a −30° one of roof planes, each as its own flight.

| SkyScan mode | Tilt (fixed per flight) | Pattern | 3D from |
|---|---|---|---|
| Crude / LANDSCAPE | −30° preset (bottom of ring leans forward: terrain profile + look-ahead) | lawnmower, nose-forward | vertical fan × forward motion (pushbroom swath: ground below + both sides, width ≈ 2·√(range²−AGL²)) |
| STRUCTURE detail | operator's choice: 0 / +30 / −30 | stacked orbits, nose-forward along the tangent | facade sits ABEAM, inside the scan plane — painted floor-to-roof every sweep; orbit motion provides the horizontal sweep, orbit levels extend coverage past lidar range on tall structures |

Why tangent (nose-forward) orbits and not nose-in: the scan plane is
perpendicular to the nose, so pointing the nose AT the facade would put the
wall parallel to the plane — never intersected. Flown tangent, the wall sits
abeam where the ring slices it vertically, and ArduPilot's default GUIDED yaw
(nose at the next waypoint) produces this for free. Orbit direction selects
which side faces the building, and the level spacing can be generous: each
level covers a facade band of ±√(max_range² − standoff²) around flight
altitude (≈ ±9 m at 8 m standoff with the C1's 12 m range).

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

No operator action at any stage. The sensor split follows the ring geometry:
**forward** = nose TFmini-S (the ring can't see there), **sides/up/down** =
the ring's wedge minima (lateral wedges tilt-immune), **down** = the altimeter
TFmini-S.

1. **Flight controller layer** — forward rangefinder + ring wedge minima
   stream to the FC as per-orientation `DISTANCE_SENSOR` (fwd / left / right /
   up / down); with `AVOID_ENABLE=7`, `PRX1_TYPE=2` the FC brakes and backs
   off on its own — and this works even if the companion process dies.
2. **Companion layer** — stop-and-hold with hysteresis: forward return inside
   `stop_distance_m`, or a side return inside `lateral_stop_m` (corridor
   guard), → BRAKE + `OBSTACLE_STOP` event; auto-resume after 3 s continuously
   clear beyond `resume_clear_m`.
3. **Escalation: detour + scan.** A hold persisting past `detour_after_s`
   means a static, unexpected object. The companion locates it (blocking
   sensor's body bearing × range × pose), flies an arc around it at a safe
   radius **nose along the tangent — so the object sits abeam, inside the
   vertical scan plane, and is scanned into the deliverable** — exits on the
   far side and rejoins the planned route. Events: `OBSTACLE_DETOUR`,
   `OBSTACLE_SCAN_START/DONE`.
4. **Geometry layer** — routes stand off the structure; the geofence
   (mission polygon + margin) is checked onboard at every waypoint, detours
   included.

**Stated honestly:** with one forward rangefinder the forward coverage is a
narrow beam, not a cone — thin obstacles (wires, branches) off-axis can be
missed. Keep cruise speed modest near structures, use the −30° tilt's
forward-down look-ahead on terrain-following passes, and see the gap list:
a second forward unit angled up, or a small solid-state forward lidar,
widens the cone cheaply. The tail is blind: the executor never commands
backward translation, and BRAKE holds position rather than reversing.

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
5. **Forward obstacle sensing** — the vertical ring covers up, down and both
   sides but is blind along the nose: the forward **TFmini-S**
   (`rangefinder.forward`, on the TCA9548A) is REQUIRED for autonomous
   flight — the pre-flight self-test FAILs without it. It is a narrow beam;
   for a wider forward cone add a second unit angled ~15° up (wires/eaves)
   or a depth camera (OAK-D/RealSense) if Jetson compute budget allows.
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

## Firmware: ArduPilot vs PX4 — the decision

The agent targets **ArduPilot Copter** (`mavlink.vehicle: arducopter`), and
for this architecture that is the right call, not just the default:

- The companion plans, the FC executes: GUIDED-mode position/velocity targets,
  DO_CHANGE_SPEED, BRAKE, RTL — all first-class and battle-tested in ArduCopter
  for close-quarters multirotor work. This code uses exactly that surface.
- Proximity + simple avoidance (`PRX1_TYPE=2`, `AVOID_ENABLE=7`) consume the
  per-orientation DISTANCE_SENSOR stream directly — the FC keeps braking on
  obstacles even if the Jetson process dies (two independent safety brains).
- RTK integration (u-blox, Unicore, NMEA rovers), rangefinder handling,
  fences, battery failsafes and resume-friendly behaviors are mature and
  well-documented.
- BRAKE mode as an obstacle-hold primitive has no equally crisp PX4
  equivalent (PX4 HOLD exists, but mode semantics and the mode-switch API
  differ enough that `mavlink_controller.py` would need a PX4 backend).

**When PX4 would earn its keep:** if avoidance and trajectory generation move
fully onto the Jetson (ROS 2 + microDDS, offboard setpoint streaming at
50–100 Hz) with the FC as a thin executor. That is a different architecture —
more capable in GNSS-denied/SLAM territory, and more work. Nothing in the
SkyScan boundary precludes migrating later: the backend contract
(`DroneConnector`) and the scan pipeline are firmware-agnostic; only
`mavlink_controller.py` (mode names, arming, avoidance parameters) and the
bring-up parameter list would change.

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
- **Tangent orbits, nose-forward**: the scan plane is perpendicular to the
  nose, so the facade must sit ABEAM to be swept — and nose-forward flight
  also points the forward rangefinder along the direction of travel, where
  the danger is.
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
   `SERIAL2_BAUD=921`, `AVOID_ENABLE=7`, `PRX1_TYPE=2` (MAVLink proximity:
   consumes the per-orientation DISTANCE_SENSOR stream), `RNGFND1_TYPE=10`
   (MAVLink, downward), battery failsafes, fence. Note: BendyRuler path
   planning (`OA_TYPE=1`) is optional now — with a single forward beam the
   FC lacks the wide horizontal scan it plans best with; simple avoidance
   (brake/backoff) plus the companion detour is the honest configuration.
2. Jetson: `pip install -r requirements.txt`, copy `config.yaml` →
   `/etc/skyscan/`, install `systemd/skyscan-drone.service`.
3. Backend: set `DRONE_CONNECTOR=jetson` — nothing else changes.
4. SITL end-to-end, then props-off bench test (tilt sweep, lidar stream,
   photo capture, telemetry in the console), then tethered hover, then the
   crude pass over an open field before any structure work.
