"""BLE state machine with automatic adapter recovery.

State flow:
    SCANNING → CONNECTING → INITING → POLLING
        ↑                                 |
        +──── BACKOFF ←───────────────────+
                 |
                 v (after N failures)
           ADAPTER_RESET
"""

import asyncio
import enum
import logging
from collections.abc import Awaitable, Callable

from bleak import BleakClient, BleakError, BleakScanner

from .config import BLEConfig
from .protocol import (
    CMD_INIT_1,
    CMD_INIT_2,
    CMD_POLL,
    NOTIFY_UUID,
    WRITE_UUID,
    TreadmillDecoder,
    TreadmillReading,
)

log = logging.getLogger("treadmill.ble")


class State(enum.Enum):
    SCANNING = "scanning"
    CONNECTING = "connecting"
    INITING = "initing"
    POLLING = "polling"
    BACKOFF = "backoff"
    ADAPTER_RESET = "adapter_reset"


class BLEManager:
    def __init__(
        self,
        config: BLEConfig,
        on_reading: Callable[[TreadmillReading], Awaitable[None]],
    ):
        self.config = config
        self._on_reading = on_reading
        self._state = State.SCANNING
        self._consecutive_failures = 0
        self._address: str | None = config.address
        self._client: BleakClient | None = None
        self._decoder = TreadmillDecoder()

    @property
    def state(self) -> State:
        return self._state

    async def run(self):
        """Drive the state machine forever."""
        while True:
            try:
                match self._state:
                    case State.SCANNING:
                        await self._scan()
                    case State.CONNECTING:
                        await self._connect()
                    case State.INITING:
                        await self._init_handshake()
                    case State.POLLING:
                        await self._poll_loop()
                    case State.BACKOFF:
                        await self._backoff()
                    case State.ADAPTER_RESET:
                        await self._reset_adapter()
            except Exception:
                log.exception("Unexpected error in state %s", self._state.value)
                self._transition(State.BACKOFF)

    def _transition(self, new_state: State):
        log.info("BLE: %s → %s", self._state.value, new_state.value)
        self._state = new_state

    # --- State handlers ---

    async def _scan(self):
        log.info("Scanning for %s...", self.config.device_name)
        try:
            devices = await BleakScanner.discover(
                timeout=self.config.scan_timeout_secs, return_adv=True
            )
        except BleakError as e:
            log.warning("Scan failed: %s", e)
            self._fail()
            return

        for addr, (device, adv) in devices.items():
            name = adv.local_name or device.name or ""
            if self.config.device_name.upper() in name.upper():
                self._address = addr
                log.info("Found %s at %s (RSSI=%d)", name, addr, adv.rssi)
                self._transition(State.CONNECTING)
                return

        log.warning("Device %s not found in scan", self.config.device_name)
        self._fail()

    async def _connect(self):
        log.info("Connecting to %s...", self._address)
        try:
            self._client = BleakClient(self._address, timeout=15)
            await self._client.connect()
            self._decoder = TreadmillDecoder()
            log.info("Connected (MTU=%d)", self._client.mtu_size)
            self._transition(State.INITING)
        except (BleakError, TimeoutError, OSError) as e:
            log.warning("Connection failed: %s", e)
            self._client = None
            self._fail()

    async def _init_handshake(self):
        log.info("Sending init handshake...")
        try:
            await self._write(CMD_INIT_1)
            await asyncio.sleep(0.3)
            await self._write(CMD_INIT_1)
            await asyncio.sleep(0.5)
            await self._write(CMD_INIT_2)
            await asyncio.sleep(0.5)

            await self._client.start_notify(NOTIFY_UUID, self._on_notify)
            log.info("Handshake complete, notifications active")
            self._consecutive_failures = 0
            self._transition(State.POLLING)
        except (BleakError, TimeoutError, OSError) as e:
            log.warning("Init handshake failed: %s", e)
            await self._disconnect()
            self._fail()

    async def _poll_loop(self):
        log.info("Polling every %.0fs...", self.config.poll_interval_secs)
        try:
            while self._state == State.POLLING:
                await self._write(CMD_POLL)
                await asyncio.sleep(self.config.poll_interval_secs)
        except (BleakError, TimeoutError, OSError) as e:
            log.warning("Poll error: %s", e)
            await self._disconnect()
            self._fail()

    async def _backoff(self):
        delay = min(
            2 ** self._consecutive_failures, self.config.max_backoff_secs
        )
        if self._consecutive_failures >= self.config.adapter_reset_after_failures:
            self._transition(State.ADAPTER_RESET)
            return
        log.info("Backing off %.0fs (failure %d)", delay, self._consecutive_failures)
        await asyncio.sleep(delay)
        self._transition(State.SCANNING)

    async def _reset_adapter(self):
        log.warning(
            "Resetting Bluetooth adapter after %d failures",
            self._consecutive_failures,
        )
        try:
            proc = await asyncio.create_subprocess_exec(
                "bluetoothctl", "power", "off",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            await asyncio.sleep(2)
            proc = await asyncio.create_subprocess_exec(
                "bluetoothctl", "power", "on",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            await asyncio.sleep(3)
            log.info("Adapter reset complete")
        except Exception:
            log.exception("Adapter reset failed")
        self._consecutive_failures = 0
        self._transition(State.SCANNING)

    # --- Helpers ---

    def _fail(self):
        self._consecutive_failures += 1
        self._transition(State.BACKOFF)

    async def _write(self, data: bytes):
        await self._client.write_gatt_char(WRITE_UUID, data, response=False)

    async def _disconnect(self):
        if self._client and self._client.is_connected:
            try:
                await self._client.disconnect()
            except Exception:
                pass
        self._client = None

    def _on_notify(self, sender, data: bytearray):
        for reading in self._decoder.feed(bytes(data)):
            asyncio.get_event_loop().create_task(self._on_reading(reading))
