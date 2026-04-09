"""
Sperax RM01 BLE protocol decoder.

The treadmill uses a wi-linktech WLT6200 BLE UART bridge with a proprietary
F5/FA framed protocol. Packets are fragmented across BLE's 20-byte MTU and
use F0-based byte stuffing for reserved values.

Frame format: [F5] [LEN] [PAYLOAD...] [CRC_HI] [CRC_LO] [FA]
Escape rule:  real byte 0xF0/0xF5/0xFA → wire bytes [F0] [byte ^ F0]
"""

from dataclasses import dataclass

# BLE UUIDs
SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Pre-computed commands (with CRC)
CMD_INIT_1 = bytes([0xF5, 0x07, 0x00, 0x01, 0x26, 0xD8, 0xFA])
CMD_INIT_2 = bytes([0xF5, 0x09, 0x00, 0x13, 0x01, 0x00, 0x89, 0xB8, 0xFA])
CMD_POLL = bytes([0xF5, 0x08, 0x00, 0x19, 0xF0, 0x0A, 0x59, 0xFA])


@dataclass(frozen=True, slots=True)
class TreadmillReading:
    time_secs: int
    speed: float
    steps: int
    distance: float


class TreadmillDecoder:
    """Reassembles fragmented BLE packets and decodes treadmill data."""

    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data: bytes) -> list[TreadmillReading]:
        """Feed raw BLE notification bytes and return any complete decoded frames."""
        self.buffer.extend(data)
        results = []
        for payload in self._extract_frames():
            decoded = self._decode_payload(payload)
            if decoded:
                results.append(decoded)
        return results

    def _extract_frames(self) -> list[bytes]:
        frames = []
        while True:
            try:
                start = self.buffer.index(0xF5)
            except ValueError:
                self.buffer.clear()
                break
            if start > 0:
                del self.buffer[:start]

            end = -1
            for j in range(2, len(self.buffer)):
                if self.buffer[j] == 0xFA and self.buffer[j - 1] != 0xF0:
                    end = j
                    break
            if end == -1:
                break

            frame = bytes(self.buffer[: end + 1])
            del self.buffer[: end + 1]
            frames.append(self._de_escape(frame))
        return frames

    @staticmethod
    def _de_escape(frame: bytes) -> bytes:
        raw = frame[2:-1]  # strip F5, LEN, FA
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

    @staticmethod
    def _decode_payload(payload: bytes) -> TreadmillReading | None:
        """Decode a de-escaped payload into treadmill metrics.

        Payload byte map (after de-escaping):
          [7-8]   Elapsed time in seconds (big-endian uint16)
          [10]    Distance × 100 in display units (uint8, wraps at 2.56)
          [13-14] Step count (big-endian uint16)
          [15]    Speed × 10 in display units (uint8)
        """
        if len(payload) < 16 or payload[1] != 0x19:
            return None

        return TreadmillReading(
            time_secs=(payload[7] << 8) | payload[8],
            steps=(payload[13] << 8) | payload[14],
            speed=payload[15] / 10.0,
            distance=payload[10] / 100.0,
        )
