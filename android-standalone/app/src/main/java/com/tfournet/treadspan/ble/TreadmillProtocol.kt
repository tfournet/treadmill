package com.tfournet.treadspan.ble

import java.io.ByteArrayOutputStream

// Sperax RM01 BLE protocol decoder.
// Frame format: F5 LEN PAYLOAD CRC_HI CRC_LO FA
// Escape rule: real byte F0/F5/FA -> wire bytes F0 (byte XOR F0)

object TreadmillProtocol {
    const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID  = "0000fff1-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID   = "0000fff2-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID    = "00002902-0000-1000-8000-00805f9b34fb"

    val CMD_INIT_1: ByteArray = byteArrayOf(0xF5.toByte(), 0x07, 0x00, 0x01, 0x26.toByte(), 0xD8.toByte(), 0xFA.toByte())
    val CMD_INIT_2: ByteArray = byteArrayOf(0xF5.toByte(), 0x09, 0x00, 0x13, 0x01, 0x00, 0x89.toByte(), 0xB8.toByte(), 0xFA.toByte())
    val CMD_POLL:   ByteArray = byteArrayOf(0xF5.toByte(), 0x08, 0x00, 0x19, 0xF0.toByte(), 0x0A, 0x59, 0xFA.toByte())
}

data class TreadmillReading(
    val timeSecs: Int,
    val speed: Double,
    val steps: Int,
    val distance: Double,
)

/**
 * Reassembles fragmented BLE packets and decodes treadmill data.
 *
 * Mirrors Python TreadmillDecoder exactly, including buffer management and
 * F5/FA frame extraction with F0 escape decoding.
 */
class TreadmillDecoder {
    private val buffer = ByteArrayOutputStream()

    fun feed(data: ByteArray): List<TreadmillReading> {
        buffer.write(data)
        val results = mutableListOf<TreadmillReading>()
        for (payload in extractFrames()) {
            val reading = decodePayload(payload)
            if (reading != null) results.add(reading)
        }
        return results
    }

    private fun extractFrames(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var buf = buffer.toByteArray()
        buffer.reset()

        while (true) {
            val start = buf.indexOfFirst { it.toInt() and 0xFF == 0xF5 }
            if (start == -1) {
                // No F5 found — discard everything
                return frames
            }
            if (start > 0) {
                buf = buf.copyOfRange(start, buf.size)
            }

            // Find FA not preceded by F0 (starting from index 2)
            var end = -1
            for (j in 2 until buf.size) {
                val b = buf[j].toInt() and 0xFF
                val prev = buf[j - 1].toInt() and 0xFF
                if (b == 0xFA && prev != 0xF0) {
                    end = j
                    break
                }
            }
            if (end == -1) {
                // Incomplete frame — keep remaining bytes in buffer
                buffer.write(buf)
                return frames
            }

            val frame = buf.copyOfRange(0, end + 1)
            buf = buf.copyOfRange(end + 1, buf.size)
            frames.add(deEscape(frame))
        }
    }

    private fun deEscape(frame: ByteArray): ByteArray {
        // Strip F5 + LEN prefix and FA suffix
        val raw = frame.copyOfRange(2, frame.size - 1)
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val b = raw[i].toInt() and 0xFF
            if (b == 0xF0 && i + 1 < raw.size) {
                out.write((raw[i + 1].toInt() and 0xFF) xor 0xF0)
                i += 2
            } else {
                out.write(b)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun decodePayload(payload: ByteArray): TreadmillReading? {
        if (payload.size < 16 || (payload[1].toInt() and 0xFF) != 0x19) return null

        val timeSecs = ((payload[7].toInt() and 0xFF) shl 8) or (payload[8].toInt() and 0xFF)
        val steps    = ((payload[13].toInt() and 0xFF) shl 8) or (payload[14].toInt() and 0xFF)
        val speed    = (payload[15].toInt() and 0xFF) / 10.0
        val distance = (payload[10].toInt() and 0xFF) / 100.0

        return TreadmillReading(
            timeSecs = timeSecs,
            speed    = speed,
            steps    = steps,
            distance = distance,
        )
    }
}
