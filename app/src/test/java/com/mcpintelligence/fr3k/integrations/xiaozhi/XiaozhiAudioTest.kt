package com.mcpintelligence.fr3k.integrations.xiaozhi

import org.junit.Assert.*
import org.junit.Test

class XiaozhiAudioTest {
    @Test fun rejectsInvalidRatesAndFrames() {
        assertThrows(IllegalArgumentException::class.java) { XiaozhiOpusCodec(44100) }
        val codec = XiaozhiOpusCodec(16000)
        assertThrows(IllegalArgumentException::class.java) { codec.encode(ShortArray(959)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(3826)) }
    }
    @Test fun allSupportedDownlinkRatesProduceCorrectDuration() {
        for (rate in listOf(8000, 12000, 16000, 24000, 48000)) {
            val codec = XiaozhiOpusCodec(rate)
            assertEquals(rate * 60 / 1000, codec.decode(codec.encode(ShortArray(960))).size)
        }
    }
    @Test fun opusRoundTripUsesSixtyMillisecondFrames() {
        val codec = XiaozhiOpusCodec(24000)
        val pcm = ShortArray(960) { (kotlin.math.sin(it * 0.1) * 12000).toInt().toShort() }
        val packet = codec.encode(pcm)
        assertTrue(packet.isNotEmpty())
        val decoded = codec.decode(packet)
        assertEquals(1440, decoded.size)
        assertTrue(decoded.any { it.toInt() != 0 })
    }
}
