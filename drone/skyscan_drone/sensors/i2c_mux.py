"""TCA9548A I2C multiplexer — routes the shared bus to one downstream device.

Several I2C devices (TFmini-S in I2C mode, AS5600 encoder, future avoidance ToFs) share
fixed addresses, so each lives behind its own mux channel. Thread-safe: all
companion I2C traffic is serialized through this class.
"""
from __future__ import annotations

import threading

try:
    from smbus2 import SMBus
except ImportError:      # SITL / dev machine
    SMBus = None

TCA_ADDR = 0x70


class I2CMux:
    _lock = threading.Lock()

    def __init__(self, bus_num: int, address: int = TCA_ADDR):
        self.bus = SMBus(bus_num) if SMBus else None
        self.address = address
        self._current = -1

    def select(self, channel: int) -> None:
        if self.bus is None:
            return
        if channel != self._current:
            self.bus.write_byte(self.address, 1 << channel)
            self._current = channel

    def locked(self):
        """with mux.locked(): mux.select(ch); ...device traffic..."""
        return I2CMux._lock
