"""LiDAR tilt axis: DS3218 servo (via PCA9685 PWM) + AS5600 magnetic encoder
(behind the TCA9548A) for closed-loop, degree-accurate positioning.

The tilt IS the payload mode switch:
  landscape/crude: plane tilted down (-30°) -> pushbroom terrain profiles
  structure:       0° -> vertical scan plane sweeping facades while orbiting
The encoder reading is stamped into the scan stream so georeferencing uses the
measured angle, not the commanded one.
"""
from __future__ import annotations

import logging
import os
import time

from .i2c_mux import I2CMux

log = logging.getLogger("tilt")

AS5600_ADDR = 0x36
AS5600_RAW_ANGLE = 0x0C

MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"


class TiltServo:
    def __init__(self, cfg: dict, mux: I2CMux):
        self.cfg = cfg
        self.mux = mux
        self._mock_angle = 0.0
        self.pca = None
        if not MOCK:
            import board, busio                       # noqa: adafruit blinka on Jetson
            from adafruit_pca9685 import PCA9685
            i2c = busio.I2C(board.SCL, board.SDA)
            self.pca = PCA9685(i2c)
            self.pca.frequency = 50

    # ------------------------------------------------------------ encoder

    def read_angle_deg(self) -> float:
        """Measured tilt from the AS5600, zeroed at the calibrated level position."""
        if MOCK:
            return self._mock_angle
        with self.mux.locked():
            self.mux.select(self.cfg["encoder_mux_channel"])
            hi = self.mux.bus.read_byte_data(AS5600_ADDR, AS5600_RAW_ANGLE)
            lo = self.mux.bus.read_byte_data(AS5600_ADDR, AS5600_RAW_ANGLE + 1)
        raw = ((hi << 8) | lo) & 0x0FFF
        deg = raw * 360.0 / 4096.0
        deg = (deg + 180) % 360 - 180
        return deg - self.cfg.get("zero_offset_deg", 0.0)

    # ------------------------------------------------------------ closed-loop move

    def move_to(self, target_deg: float, timeout_s: float = 4.0) -> float:
        """Command + verify via encoder; small proportional trim on the pulse.
        Returns the settled measured angle."""
        target = max(self.cfg["min_deg"], min(self.cfg["max_deg"], target_deg))
        if MOCK:
            self._mock_angle = target
            return target
        pulse = self._deg_to_pulse(target)
        t0 = time.time()
        while time.time() - t0 < timeout_s:
            self._set_pulse(pulse)
            time.sleep(0.15)
            measured = self.read_angle_deg()
            err = target - measured
            if abs(err) <= self.cfg["settle_tolerance_deg"]:
                log.info("tilt settled at %.2f° (target %.2f°)", measured, target)
                return measured
            pulse += err * 2.0                        # µs per degree trim (calibrate)
        measured = self.read_angle_deg()
        log.warning("tilt did not settle: target %.2f°, measured %.2f°", target, measured)
        return measured

    def _deg_to_pulse(self, deg: float) -> float:
        # DS3218: ~500µs..2500µs over 180°; our axis is geared for ±30° travel.
        return 1500.0 + deg * (1000.0 / 90.0)

    def _set_pulse(self, us: float) -> None:
        duty = int(us / 20000.0 * 0xFFFF)
        self.pca.channels[self.cfg["pca9685_channel"]].duty_cycle = duty
