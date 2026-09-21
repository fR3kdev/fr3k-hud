package com.mcpintelligence.fr3k.integrations.blackwave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackwaveUsbRecoveryProtocolTest {
    @Test fun roundTripPreservesTypeSequenceAndPayload() {
        val input = UsbRecoveryFrame(
            UsbRecoveryType.RECONNECT_WIRELESS,
            sequence = 513,
            payload = """{"reason":"wifi_lost"}""".toByteArray(),
        )
        val frames = BlackwaveUsbRecoveryDecoder().feed(BlackwaveUsbRecoveryCodec.encode(input))
        assertEquals(1, frames.size)
        assertEquals(input.type, frames.single().type)
        assertEquals(input.sequence, frames.single().sequence)
        assertArrayEquals(input.payload, frames.single().payload)
    }

    @Test fun partialReadsAreRetainedUntilFrameCompletes() {
        val encoded = BlackwaveUsbRecoveryCodec.encode(
            UsbRecoveryFrame(UsbRecoveryType.HELLO, 7, "hello".toByteArray()),
        )
        val decoder = BlackwaveUsbRecoveryDecoder()
        assertTrue(decoder.feed(encoded.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.feed(encoded.copyOfRange(5, encoded.size))
        assertEquals("hello", frames.single().payload.toString(Charsets.UTF_8))
    }

    @Test fun junkPrefixResynchronizesToBlackwaveMagic() {
        val valid = BlackwaveUsbRecoveryCodec.encode(
            UsbRecoveryFrame(UsbRecoveryType.STATUS, 9, byteArrayOf(1, 2, 3)),
        )
        val frames = BlackwaveUsbRecoveryDecoder().feed(byteArrayOf(9, 8, 7) + valid)
        assertEquals(1, frames.size)
        assertEquals(UsbRecoveryType.STATUS, frames.single().type)
    }

    @Test fun wrongVersionIsRejectedAndNextFrameCanStillBeDecoded() {
        val bad = BlackwaveUsbRecoveryCodec.encode(
            UsbRecoveryFrame(UsbRecoveryType.HELLO, 1, byteArrayOf()),
        ).also { it[2] = 99 }
        val good = BlackwaveUsbRecoveryCodec.encode(
            UsbRecoveryFrame(UsbRecoveryType.HELLO, 2, "ok".toByteArray()),
        )
        val frames = BlackwaveUsbRecoveryDecoder().feed(bad + good)
        assertEquals(1, frames.size)
        assertEquals(2, frames.single().sequence)
    }

    @Test fun oversizedLengthIsRejectedWithoutAllocatingPayload() {
        val bad = byteArrayOf(
            0x42, 0x57, 0x01, 0x01, 0x01, 0x00, 0xFF.toByte(), 0x7F,
        )
        assertTrue(BlackwaveUsbRecoveryDecoder().feed(bad).isEmpty())
    }

    @Test fun encoderRejectsPayloadAboveBound() {
        val payload = ByteArray(BlackwaveUsbRecoveryCodec.MAX_PAYLOAD_SIZE + 1)
        try {
            BlackwaveUsbRecoveryCodec.encode(
                UsbRecoveryFrame(UsbRecoveryType.STATUS, 1, payload),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
