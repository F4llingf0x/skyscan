"""TFmini-S ranging module (orientation-agnostic): one instance per unit.

Two units fly on this airframe:
  down    — precise AGL, terrain following, gentle landings (the altimeter)
  forward — the avoidance range along the nose: the C1's vertical ring is
            blind fore/aft, so this sensor IS the forward obstacle input
            (companion stop-and-hold + FC DISTANCE_SENSOR injection)

With 12 m of range (vs the 4 m of small ToF chips) the TFmini-S is a genuine
avoidance-class sensor, not just a touchdown probe.

Two wiring modes (config `rangefinder.mode`):
  uart — factory default: 115200 8N1, 9-byte frames [0x59 0x59 dL dH sL sH tL tH ck]
  i2c  — after switching the module to I2C mode: address 0x10, behind the
         TCA9548A mux channel (frame layout identical)

Field note: the TFmini-S is 850 nm IR — strong direct sunlight on low-albedo
surfaces shortens usable range; treat >8 m readings outdoors as advisory.
"""
from __future__ import annotations

import logging
import os
import threading
import time

from .i2c_mux import I2CMux

log = logging.getLogger("rangefinder")
MOCK = os.environ.get("SKYSCAN_MOCK_SENSORS") == "1"

TFMINI_I2C_ADDR = 0x10
MAX_RANGE_M = 12.0


class TfMiniRangefinder:
    def __init__(self, cfg: dict, mux: I2CMux, publish):
        self.cfg, self.mux, self.publish = cfg, mux, publish
        self._running = False
        self.last: tuple[float, float] | None = None      # (timestamp, dist_m)
        self.last_strength: int = 0
        self._serial = None
        if not MOCK and cfg.get("mode", "uart") == "uart":
            import serial
            self._serial = serial.Serial(cfg.get("port", "/dev/ttyTHS2"),
                                         cfg.get("baud", 115200), timeout=0.2)

    def start(self) -> None:
        self._running = True
        threading.Thread(target=self._loop, daemon=True,
                         name=f"tfmini-{self.cfg.get('name', 's')}").start()

    # ------------------------------------------------------------ acquisition

    def _loop(self) -> None:
        period = 1.0 / self.cfg["publish_hz"]
        while self._running:
            try:
                dist_m, strength = self._read()
                if dist_m is not None and 0.1 < dist_m < MAX_RANGE_M and strength >= 100:
                    self.last = (time.time(), dist_m)
                    self.last_strength = strength
                    self.publish(dist_m)
            except Exception as e:
                log.debug("rangefinder read failed: %s", e)
            time.sleep(period)

    def _read(self) -> tuple[float | None, int]:
        if MOCK:
            return 3.0, 800
        if self._serial is not None:
            return self._read_uart()
        return self._read_i2c()

    def _read_uart(self) -> tuple[float | None, int]:
        # resync on the 0x59 0x59 header, then parse one frame
        for _ in range(32):
            if self._serial.read(1) == b"\x59" and self._serial.read(1) == b"\x59":
                rest = self._serial.read(7)
                if len(rest) != 7:
                    return None, 0
                frame = b"\x59\x59" + rest
                if (sum(frame[:8]) & 0xFF) != frame[8]:
                    continue                                   # checksum miss: resync
                dist_cm = frame[2] | (frame[3] << 8)
                strength = frame[4] | (frame[5] << 8)
                return dist_cm / 100.0, strength
        return None, 0

    def _read_i2c(self) -> tuple[float | None, int]:
        with self.mux.locked():
            self.mux.select(self.cfg["mux_channel"])
            # trigger + read one data frame (see Benewake TFmini-S I2C protocol)
            self.mux.bus.write_i2c_block_data(TFMINI_I2C_ADDR, 0x5A, [0x05, 0x00, 0x01, 0x60])
            time.sleep(0.01)
            frame = self.mux.bus.read_i2c_block_data(TFMINI_I2C_ADDR, 0x00, 9)
        if frame[0] != 0x59 or frame[1] != 0x59 or (sum(frame[:8]) & 0xFF) != frame[8]:
            return None, 0
        return (frame[2] | (frame[3] << 8)) / 100.0, frame[4] | (frame[5] << 8)

    def stop(self) -> None:
        self._running = False
        if self._serial:
            self._serial.close()


# Backwards-compatible alias (pre-vertical-ring builds)
DownwardRangefinder = TfMiniRangefinder
