package com.mcpintelligence.fr3k.integrations.blackwave

/**
 * PROPOSED fr3k-blackwave-usb-recovery/1 framing.
 *
 * Recovery-only transport. It does not redefine BLACKWAVE authority and must
 * not be considered available until the peer completes the versioned HELLO.
 *
 * Framing structure is adapted from the streaming approach used by the
 * MIT-licensed NRSuite Android FrameCodec (copyright 2026 J457), but uses
 * BLACKWAVE-specific magic, versioning, types and bounds.
 */
enum class UsbRecoveryType(val code: Int) {
    HELLO(1),
    STATUS(2),
    RECONNECT_WIRELESS(3),
    RESPONSE(4),
    ERROR(5);

    companion object {
        fun fromCode(code: Int): UsbRecoveryType? =
            entries.firstOrNull { it.code == code }
    }
}

data class UsbRecoveryFrame(
    val type: UsbRecoveryType,
    val sequence: Int,
    val payload: ByteArray = ByteArray(0),
)
object BlackwaveUsbRecoveryCodec {
    const val MAGIC_0 = 0x42 // B
    const val MAGIC_1 = 0x57 // W
    const val VERSION = 1
    const val HEADER_SIZE = 8
    const val MAX_PAYLOAD_SIZE = 2048

    fun encode(frame: UsbRecoveryFrame): ByteArray {
        require(frame.sequence in 0..0xFFFF)
        require(frame.payload.size <= MAX_PAYLOAD_SIZE)

        return ByteArray(HEADER_SIZE + frame.payload.size).also { out ->
            out[0] = MAGIC_0.toByte()
            out[1] = MAGIC_1.toByte()
            out[2] = VERSION.toByte()
            out[3] = frame.type.code.toByte()
            out[4] = (frame.sequence and 0xFF).toByte()
            out[5] = ((frame.sequence ushr 8) and 0xFF).toByte()
            out[6] = (frame.payload.size and 0xFF).toByte()
            out[7] = ((frame.payload.size ushr 8) and 0xFF).toByte()
            frame.payload.copyInto(out, HEADER_SIZE)
        }
    }
}
class BlackwaveUsbRecoveryDecoder {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(data: ByteArray): List<UsbRecoveryFrame> {
        if (data.isNotEmpty()) buffer += data
        val frames = mutableListOf<UsbRecoveryFrame>()
        var offset = 0

        while (offset < buffer.size) {
            val start = findMagic(offset)
            if (start < 0) {
                offset = if (buffer.lastOrNull() == BlackwaveUsbRecoveryCodec.MAGIC_0.toByte()) {
                    buffer.size - 1
                } else {
                    buffer.size
                }
                break
            }
            offset = start
            if (buffer.size - offset < BlackwaveUsbRecoveryCodec.HEADER_SIZE) break

            val version = buffer[offset + 2].toInt() and 0xFF
            val type = UsbRecoveryType.fromCode(buffer[offset + 3].toInt() and 0xFF)
            val sequence = (buffer[offset + 4].toInt() and 0xFF) or
                ((buffer[offset + 5].toInt() and 0xFF) shl 8)
            val payloadSize = (buffer[offset + 6].toInt() and 0xFF) or
                ((buffer[offset + 7].toInt() and 0xFF) shl 8)

            if (version != BlackwaveUsbRecoveryCodec.VERSION ||
                type == null ||
                payloadSize > BlackwaveUsbRecoveryCodec.MAX_PAYLOAD_SIZE
            ) {
                offset += 1
                continue
            }

            val total = BlackwaveUsbRecoveryCodec.HEADER_SIZE + payloadSize
            if (buffer.size - offset < total) break
            val payloadStart = offset + BlackwaveUsbRecoveryCodec.HEADER_SIZE
            frames += UsbRecoveryFrame(
                type = type,
                sequence = sequence,
                payload = buffer.copyOfRange(payloadStart, payloadStart + payloadSize),
            )
            offset += total
        }

        buffer = buffer.copyOfRange(offset.coerceAtMost(buffer.size), buffer.size)
        return frames
    }

    private fun findMagic(from: Int): Int {
        var index = from
        while (index + 1 < buffer.size) {
            if (buffer[index] == BlackwaveUsbRecoveryCodec.MAGIC_0.toByte() &&
                buffer[index + 1] == BlackwaveUsbRecoveryCodec.MAGIC_1.toByte()
            ) {
                return index
            }
            index += 1
        }
        return -1
    }
}
