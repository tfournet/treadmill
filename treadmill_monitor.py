#!/usr/bin/env python3
"""
Sperax RM01 Treadmill BLE Monitor

Connects to a Sperax RM01 treadmill via BLE and displays live data.
Protocol reverse-engineered from the wi-linktech WLT6200 BLE UART bridge.

Usage:
    python3 treadmill_monitor.py [--addr XX:XX:XX:XX:XX:XX] [--imperial]
"""

import asyncio
import argparse
import signal
import sys
from datetime import datetime

from bleak import BleakClient, BleakScanner

# BLE UUIDs
SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Protocol commands (pre-computed with CRC)
CMD_INIT_1 = bytes([0xF5, 0x07, 0x00, 0x01, 0x26, 0xD8, 0xFA])
CMD_INIT_2 = bytes([0xF5, 0x09, 0x00, 0x13, 0x01, 0x00, 0x89, 0xB8, 0xFA])
CMD_POLL = bytes([0xF5, 0x08, 0x00, 0x19, 0xF0, 0x0A, 0x59, 0xFA])


class TreadmillDecoder:
    """Reassembles fragmented BLE packets and decodes treadmill data."""

    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data: bytes) -> list[dict]:
        """Feed raw BLE notification bytes and return any complete decoded frames."""
        self.buffer.extend(data)
        results = []
        for payload in self._extract_frames():
            decoded = self._decode_payload(payload)
            if decoded:
                results.append(decoded)
        return results

    def _extract_frames(self) -> list[bytes]:
        """Extract complete F5...FA frames from the buffer."""
        frames = []
        while True:
            # Find start marker
            try:
                start = self.buffer.index(0xF5)
            except ValueError:
                self.buffer.clear()
                break
            if start > 0:
                self.buffer = self.buffer[start:]

            # Find end marker (FA not preceded by F0 escape)
            end = -1
            for j in range(2, len(self.buffer)):
                if self.buffer[j] == 0xFA and self.buffer[j - 1] != 0xF0:
                    end = j
                    break
            if end == -1:
                break

            frame = bytes(self.buffer[: end + 1])
            self.buffer = self.buffer[end + 1 :]
            frames.append(self._de_escape(frame))
        return frames

    def _de_escape(self, frame: bytes) -> bytes:
        """Remove F5, LEN, FA wrapper and decode F0-escaped bytes."""
        raw = frame[2:-1]  # strip F5, LEN byte, and FA
        out = bytearray()
        i = 0
        while i < len(raw):
            if raw[i] == 0xF0 and i + 1 < len(raw):
                out.append(raw[i + 1] ^ 0xF0)
                i += 2
            else:
                out.append(raw[i])
                i += 1
        return bytes(out)

    def _decode_payload(self, payload: bytes) -> dict | None:
        """Decode a de-escaped payload into treadmill metrics.

        Payload byte map (after de-escaping, relative to payload start):
          [7-8]   Elapsed time in seconds (big-endian uint16)
          [10]    Distance × 100 in display units (uint8)
          [13-14] Step count (big-endian uint16)
          [15]    Speed × 10 in display units (uint8)
        """
        if len(payload) < 16 or payload[1] != 0x19:
            return None

        time_secs = (payload[7] << 8) | payload[8]
        steps = (payload[13] << 8) | payload[14]
        speed_raw = payload[15]
        distance_raw = payload[10]

        return {
            "time_secs": time_secs,
            "time_fmt": f"{time_secs // 60}:{time_secs % 60:02d}",
            "speed": speed_raw / 10.0,
            "steps": steps,
            "distance": distance_raw / 100.0,
        }


class TreadmillMonitor:
    """Connects to the treadmill and displays live data."""

    def __init__(self, address: str):
        self.address = address
        self.decoder = TreadmillDecoder()
        self.running = True
        self.latest: dict | None = None
        self.prev_steps: int | None = None
        self.prev_time: int | None = None

    def _on_notify(self, sender, data: bytearray):
        for frame in self.decoder.feed(bytes(data)):
            self.latest = frame

    async def connect_and_init(self, client: BleakClient):
        """Perform the handshake to unlock live data."""
        await client.start_notify(NOTIFY_UUID, self._on_notify)
        await asyncio.sleep(0.3)

        # Init handshake (send init1 twice, then init2)
        await client.write_gatt_char(WRITE_UUID, CMD_INIT_1, response=False)
        await asyncio.sleep(0.3)
        await client.write_gatt_char(WRITE_UUID, CMD_INIT_1, response=False)
        await asyncio.sleep(0.5)
        await client.write_gatt_char(WRITE_UUID, CMD_INIT_2, response=False)
        await asyncio.sleep(0.5)

    async def run(self):
        """Main monitoring loop."""
        print(f"Connecting to {self.address}...")
        # Scan first to populate BlueZ cache
        await BleakScanner.discover(timeout=5)
        async with BleakClient(self.address, timeout=15) as client:
            print("Connected! Initializing...")
            await self.connect_and_init(client)
            print("Live data stream active.\n")

            header = f"{'Time':>8}  {'Speed':>6}  {'Dist':>6}  {'Steps':>6}  {'Steps/m':>7}"
            print(header)
            print("-" * len(header))

            while self.running:
                await client.write_gatt_char(WRITE_UUID, CMD_POLL, response=False)
                await asyncio.sleep(1)

                if self.latest:
                    d = self.latest
                    spm = ""
                    if self.prev_steps is not None and self.prev_time is not None:
                        dt = d["time_secs"] - self.prev_time
                        ds = d["steps"] - self.prev_steps
                        if dt > 0:
                            spm = f"{ds / dt * 60:>5.0f}/m"
                    self.prev_steps = d["steps"]
                    self.prev_time = d["time_secs"]

                    print(
                        f"{d['time_fmt']:>8}  "
                        f"{d['speed']:>5.1f}  "
                        f"{d['distance']:>5.2f}  "
                        f"{d['steps']:>6d}  "
                        f"{spm:>7}"
                    )
                    self.latest = None


async def find_treadmill() -> str | None:
    """Scan for a SPERAX_RM01 device and return its address."""
    print("Scanning for SPERAX_RM01...")
    devices = await BleakScanner.discover(timeout=10, return_adv=True)
    for addr, (device, adv) in devices.items():
        name = adv.local_name or device.name or ""
        if "SPERAX" in name.upper():
            print(f"Found: {name} at {addr} (RSSI={adv.rssi})")
            return addr
    return None


async def main():
    parser = argparse.ArgumentParser(description="Sperax RM01 Treadmill Monitor")
    parser.add_argument("--addr", help="BLE address (auto-scans if omitted)")
    args = parser.parse_args()

    address = args.addr
    if not address:
        address = await find_treadmill()
        if not address:
            print("No SPERAX treadmill found. Make sure it's powered on.")
            sys.exit(1)

    monitor = TreadmillMonitor(address)

    # Handle Ctrl+C gracefully
    def stop(sig, frame):
        print("\nStopping...")
        monitor.running = False

    signal.signal(signal.SIGINT, stop)

    await monitor.run()


if __name__ == "__main__":
    asyncio.run(main())
