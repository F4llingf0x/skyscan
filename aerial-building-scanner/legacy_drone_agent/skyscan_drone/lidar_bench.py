"""Tilt-lidar BENCH TEST — exercise the RPLidar C1 + tilt axis on the Orin
Nano in isolation (no agent, no flight controller, no backend) and watch the
output live in the terminal (works over SSH).

    python3 -m skyscan_drone.lidar_bench [options]

Tilt-angle source — the whole point of the tool:
  --encoder      read the AS5600 on the tilt AXIS (behind the TCA9548A);
                 gear ratio does NOT affect the reading, only the command.
  --no-encoder   ESTIMATE the axis angle from the commanded target with a
                 servo motion model: the estimate slews toward the target at
                 (servo speed / gear ratio) deg/s and is clearly labelled
                 ESTIMATED in the display. Use this before the encoder is
                 wired, or to sanity-check a suspicious encoder.

Mechanics are selectable per build:
  --max-deg R        axis travel limit, ± degrees        (default 30)
  --gear-ratio G     cogwheel ratio, servo°/axis°        (default 3.0)
                     pulse = 1500 µs + axis° · G · (1000/90) µs
  --servo-speed S    unloaded servo speed, deg/s at the SERVO horn
                     (default 300 ≈ DS3218 at 6 V under light load);
                     axis slew for the estimator = S / G

Interactive keys:  ←/→ or -/+  step tilt   0 center   s sweep   e toggle
encoder/estimate   w write one revolution to CSV   q quit.
Non-interactive:   --tilt D --seconds N  (fixed angle, timed run, e.g. CI).
Desk test without any hardware: --mock  (synthesizes lidar + servo).

Display: live wedge minima (up/down/left/right or fwd/back/left/right per
--plane), nearest return, scan rate, an ASCII polar map of the ring, and the
tilt readout with its source. CSV snapshots land in /tmp/lidar_bench_*.csv
with the tilt angle stamped per row.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import select
import sys
import termios
import time
import tty

# ----------------------------------------------------------------- servo drive


class BenchServo:
    """Minimal PCA9685 drive with a selectable cogwheel ratio.

    The production TiltServo assumes the shipped ±30° gearing; on the bench the
    ratio is a parameter so any test rig can be driven correctly:
    servo° = axis° · gear_ratio, pulse = 1500 + servo° · (1000/90) µs.
    """

    SERVO_PHYS_DEG = 90.0          # DS3218 travel each side of center

    def __init__(self, args):
        self.gear = args.gear_ratio
        self.max_deg = args.max_deg
        self.target_deg = 0.0
        self.mock = args.mock
        self.pca = None
        self.channel = args.pca_channel
        if not self.mock:
            import board, busio                       # noqa: adafruit blinka
            from adafruit_pca9685 import PCA9685
            i2c = busio.I2C(board.SCL, board.SDA)
            self.pca = PCA9685(i2c)
            self.pca.frequency = 50

    def command(self, axis_deg: float) -> float:
        """Clamp to the axis limit AND the servo's own physical travel."""
        axis = max(-self.max_deg, min(self.max_deg, axis_deg))
        servo = max(-self.SERVO_PHYS_DEG, min(self.SERVO_PHYS_DEG, axis * self.gear))
        axis = servo / self.gear                       # re-derive after servo clamp
        self.target_deg = axis
        if not self.mock:
            us = 1500.0 + servo * (1000.0 / 90.0)
            self.pca.channels[self.channel].duty_cycle = int(us / 20000.0 * 0xFFFF)
        return axis


class AngleEstimator:
    """--no-encoder: model the axis angle from the command history.

    First-order slew: the estimate moves toward the commanded target at
    servo_speed / gear_ratio deg/s (the cogwheel slows the axis by the same
    factor it multiplies torque). Good to a degree or two on a healthy rig;
    it can NOT see stalls, slop or a browned-out servo — that is what the
    encoder is for, and why the display labels this ESTIMATED.
    """

    def __init__(self, gear_ratio: float, servo_speed_dps: float):
        self.axis_rate = servo_speed_dps / gear_ratio
        self.est = 0.0
        self._t = time.time()

    def update(self, target: float) -> float:
        now = time.time()
        dt, self._t = now - self._t, now
        step = self.axis_rate * dt
        err = target - self.est
        self.est = target if abs(err) <= step else self.est + math.copysign(step, err)
        return self.est


class EncoderReader:
    """AS5600 on the tilt AXIS behind the TCA9548A (reads axis°, gear-free)."""

    AS5600_ADDR, RAW_ANGLE = 0x36, 0x0C

    def __init__(self, args):
        self.zero = args.tilt_zero
        self.mock = args.mock
        self._mock_src = None                          # set by the tool: estimator
        if not self.mock:
            from .sensors.i2c_mux import I2CMux
            self.mux = I2CMux(args.i2c_bus)
            self.chan = args.encoder_mux_channel

    def read(self) -> float:
        if self.mock:
            return self._mock_src() if self._mock_src else 0.0
        with self.mux.locked():
            self.mux.select(self.chan)
            hi = self.mux.bus.read_byte_data(self.AS5600_ADDR, self.RAW_ANGLE)
            lo = self.mux.bus.read_byte_data(self.AS5600_ADDR, self.RAW_ANGLE + 1)
        deg = (((hi << 8) | lo) & 0x0FFF) * 360.0 / 4096.0
        return (deg + 180) % 360 - 180 - self.zero


# ----------------------------------------------------------------- display


def wedge_labels(plane: str) -> tuple[str, str, str, str]:
    # (angle 0, angle ±180, angle -90, angle +90)
    return (("UP", "DOWN", "LEFT", "RIGHT") if plane == "vertical"
            else ("FWD", "BACK", "LEFT", "RIGHT"))


def bar(d: float, max_m: float, width: int = 24) -> str:
    if not math.isfinite(d):
        return "·" * width + "   clear"
    n = max(1, min(width, int(d / max_m * width)))
    return "#" * n + "·" * (width - n) + f" {d:5.2f} m"


def polar_map(pts, max_m: float, plane: str, rows: int = 17, cols: int = 41) -> list[str]:
    """ASCII map of the scan plane. Screen up = angle 0 (UP or FWD per plane)."""
    grid = [[" "] * cols for _ in range(rows)]
    cy, cx = rows // 2, cols // 2
    for p in pts:
        r = min(p.dist_m, max_m) / max_m
        a = math.radians(p.angle_deg)
        x = cx + int(round(math.sin(a) * r * (cols // 2 - 1)))
        y = cy - int(round(math.cos(a) * r * (rows // 2 - 1)))
        if 0 <= y < rows and 0 <= x < cols:
            grid[y][x] = "*"
    grid[cy][cx] = "+"
    u, d, l, r = wedge_labels(plane)
    lines = ["".join(row) for row in grid]
    lines[0] = lines[0][: cx - len(u) // 2] + u + lines[0][cx + (len(u) + 1) // 2:]
    lines[-1] = lines[-1][: cx - len(d) // 2] + d + lines[-1][cx + (len(d) + 1) // 2:]
    lines[cy] = l[0] + lines[cy][1:-1] + r[0]
    return lines


# ----------------------------------------------------------------- main loop


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", default="/dev/ttyUSB0", help="RPLidar C1 serial port")
    ap.add_argument("--plane", choices=("vertical", "horizontal"), default="vertical",
                    help="mount plane (labels + wedge names only)")
    ap.add_argument("--max-range", type=float, default=12.0)
    ap.add_argument("--angle-zero", type=float, default=0.0,
                    help="lidar encoder angle pointing UP/NOSE (see config.yaml)")
    enc = ap.add_mutually_exclusive_group()
    enc.add_argument("--encoder", dest="encoder", action="store_true", default=True,
                     help="tilt angle from the AS5600 (default)")
    enc.add_argument("--no-encoder", dest="encoder", action="store_false",
                     help="tilt angle ESTIMATED from the command + motion model")
    ap.add_argument("--max-deg", type=float, default=30.0,
                    help="axis travel limit, ± degrees (selectable per rig)")
    ap.add_argument("--gear-ratio", type=float, default=3.0,
                    help="cogwheel ratio servo°/axis° (selectable per rig)")
    ap.add_argument("--servo-speed", type=float, default=300.0,
                    help="servo speed deg/s at the horn, for the no-encoder estimate")
    ap.add_argument("--pca-channel", type=int, default=0)
    ap.add_argument("--i2c-bus", type=int, default=7)
    ap.add_argument("--encoder-mux-channel", type=int, default=2)
    ap.add_argument("--tilt-zero", type=float, default=0.0,
                    help="encoder zero offset (config tilt.zero_offset_deg)")
    ap.add_argument("--tilt", type=float, default=0.0, help="initial/fixed tilt command")
    ap.add_argument("--sweep", action="store_true", help="auto-sweep between ±max-deg")
    ap.add_argument("--seconds", type=float, default=0.0,
                    help="non-interactive: run this long, print a summary, exit")
    ap.add_argument("--hz", type=float, default=5.0, help="display refresh rate")
    ap.add_argument("--mock", action="store_true",
                    help="no hardware: synthetic lidar + servo (desk test)")
    return ap.parse_args(argv)


class Bench:
    def __init__(self, args):
        self.args = args
        if args.mock:
            os.environ["SKYSCAN_MOCK_SENSORS"] = "1"
        from .sensors.rplidar_c1 import RpLidarC1       # import AFTER env is set
        self.latest_rev = []
        self.lidar = RpLidarC1({"port": args.port, "plane": args.plane,
                                "scan_hz": 10, "max_range_m": args.max_range,
                                "angle_zero_deg": args.angle_zero},
                               on_points=self._on_points)
        self.servo = BenchServo(args)
        self.estimator = AngleEstimator(args.gear_ratio, args.servo_speed)
        self.encoder = None
        if args.encoder:
            try:
                self.encoder = EncoderReader(args)
                if args.mock:
                    self.encoder._mock_src = lambda: self.estimator.est
            except Exception as e:
                print(f"encoder unavailable ({e}) — falling back to ESTIMATE", file=sys.stderr)
        self.use_encoder = self.encoder is not None
        self.sweeping = args.sweep
        self._sweep_dir = 1.0
        self.target = self.servo.command(args.tilt)

    def _on_points(self, pts):
        self.latest_rev = pts

    # ---------------------------------------------------------- tilt

    def tilt_deg(self) -> tuple[float, str]:
        est = self.estimator.update(self.target)        # model always runs
        if self.use_encoder:
            try:
                return self.encoder.read(), "ENCODER (AS5600)"
            except Exception:
                return est, "ESTIMATED (encoder read failed!)"
        return est, "ESTIMATED (no encoder)"

    def step_sweep(self):
        if not self.sweeping:
            return
        nxt = self.target + self._sweep_dir * (self.args.max_deg / 20.0)
        if abs(nxt) >= self.args.max_deg:
            self._sweep_dir *= -1
            nxt = math.copysign(self.args.max_deg, nxt)
        self.target = self.servo.command(nxt)

    # ---------------------------------------------------------- output

    def write_csv(self, tilt: float) -> str:
        path = f"/tmp/lidar_bench_{int(time.time())}.csv"
        with open(path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["angle_deg", "dist_m", "quality", "tilt_deg"])
            for p in self.latest_rev:
                w.writerow([f"{p.angle_deg:.2f}", f"{p.dist_m:.3f}", p.quality, f"{tilt:.2f}"])
        return path

    def frame(self) -> str:
        a = self.args
        tilt, src = self.tilt_deg()
        age, rate = self.lidar.stream_stats()
        u, d, l, r = wedge_labels(a.plane)
        tilt_note = ("+ = top of ring toward nose" if a.plane == "vertical"
                     else "+ = nose ray up")
        lines = [
            f"SkyScan tilt-lidar bench — plane={a.plane}  gear={a.gear_ratio:g}:1  "
            f"limit=±{a.max_deg:g}°  {'SWEEP' if self.sweeping else 'HOLD '}",
            f"lidar: {rate:4.1f} scans/s  {len(self.latest_rev):4d} pts/rev  "
            f"last {age*1000:5.0f} ms ago" + ("   [MOCK]" if a.mock else ""),
            f"tilt : target {self.target:+6.2f}°   actual {tilt:+6.2f}°   source: {src}",
            f"       servo horn ≈ {tilt*a.gear_ratio:+7.2f}°   ({tilt_note})",
            "",
            f"  {u:>5}  {bar(self.lidar.up_min_m if a.plane=='vertical' else self.lidar.forward_min_m, a.max_range)}",
            f"  {d:>5}  {bar(self.lidar.down_min_m if a.plane=='vertical' else float('inf'), a.max_range)}"
            if a.plane == "vertical" else
            f"  {d:>5}  {bar(min((p.dist_m for p in self.latest_rev if abs(abs(p.angle_deg)-180)<=25), default=float('inf')), a.max_range)}",
            f"  {l:>5}  {bar(self.lidar.left_min_m, a.max_range)}",
            f"  {r:>5}  {bar(self.lidar.right_min_m, a.max_range)}",
            f"  near   " + ("—" if self.lidar.nearest is None else
                            f"{self.lidar.nearest[1]:5.2f} m @ {self.lidar.nearest[0]:+7.1f}°"),
            "",
        ]
        lines += polar_map(self.latest_rev, a.max_range, a.plane)
        lines += ["", "keys: ±/arrows tilt · 0 center · s sweep · e encoder/estimate"
                      " · w csv · q quit"]
        return "\n".join(lines)


def interactive(bench: Bench, args):
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    tty.setcbreak(fd)
    msg = ""
    try:
        period = 1.0 / args.hz
        while True:
            bench.step_sweep()
            sys.stdout.write("\x1b[2J\x1b[H" + bench.frame() + ("\n" + msg if msg else "") + "\n")
            sys.stdout.flush()
            t_end = time.time() + period
            while time.time() < t_end:
                if select.select([sys.stdin], [], [], 0.05)[0]:
                    k = sys.stdin.read(1)
                    step = args.max_deg / 15.0
                    if k == "q":
                        return
                    if k in "+=" or k == "C":          # C = right arrow tail
                        bench.target = bench.servo.command(bench.target + step)
                    elif k in "-_" or k == "D":        # D = left arrow tail
                        bench.target = bench.servo.command(bench.target - step)
                    elif k == "0":
                        bench.target = bench.servo.command(0.0)
                    elif k == "s":
                        bench.sweeping = not bench.sweeping
                    elif k == "e":
                        if bench.encoder is None:
                            msg = "no encoder configured — staying on ESTIMATE"
                        else:
                            bench.use_encoder = not bench.use_encoder
                            msg = f"tilt source -> {'ENCODER' if bench.use_encoder else 'ESTIMATE'}"
                    elif k == "w":
                        tilt, _ = bench.tilt_deg()
                        msg = "wrote " + bench.write_csv(tilt)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


def timed(bench: Bench, args):
    t0 = time.time()
    while time.time() - t0 < args.seconds:
        bench.step_sweep()
        time.sleep(1.0 / args.hz)
    print(bench.frame())
    tilt, src = bench.tilt_deg()
    age, rate = bench.lidar.stream_stats()
    ok = rate > 0 and age < 2
    print(f"\nSUMMARY: lidar {'OK' if ok else 'NO DATA'} ({rate:.1f} scans/s); "
          f"tilt {tilt:+.2f}° [{src}] target {bench.target:+.2f}°")
    return 0 if ok else 1


def main() -> None:
    args = parse_args()
    bench = Bench(args)
    bench.lidar.start()
    time.sleep(0.5)
    try:
        if args.seconds > 0 or not sys.stdin.isatty():
            sys.exit(timed(bench, args if args.seconds > 0 else
                           argparse.Namespace(**{**vars(args), "seconds": 3.0})))
        interactive(bench, args)
    finally:
        bench.lidar.stop()


if __name__ == "__main__":
    main()
