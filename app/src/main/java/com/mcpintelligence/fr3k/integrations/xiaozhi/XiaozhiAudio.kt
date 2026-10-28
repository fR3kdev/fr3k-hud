package com.mcpintelligence.fr3k.integrations.xiaozhi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Raw Opus packets, never Ogg. Separate instances must be used for each session. */
class XiaozhiOpusCodec(val downlinkSampleRate: Int) {
    init { require(downlinkSampleRate in setOf(8000, 12000, 16000, 24000, 48000)) }
    private val encoder = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    private val decoder = OpusDecoder(downlinkSampleRate, 1)
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == 960)
        val output = ByteArray(4000)
        val count = encoder.encode(pcm, 0, pcm.size, output, 0, output.size)
        check(count > 0)
        return output.copyOf(count)
    }
    fun decode(packet: ByteArray): ShortArray {
        require(packet.isNotEmpty() && packet.size <= 1275 * 3)
        val pcm = ShortArray(downlinkSampleRate * 120 / 1000)
        val count = decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false)
        check(count > 0)
        return pcm.copyOf(count)
    }
}

/** Audio engine with independent capture and playback lifetimes.
 *
 * Playback starts once, when the server hello has negotiated [downlinkSampleRate]; capture is
 * started and stopped per push-to-talk turn so releasing the button never cuts the reply.
 * [sendPacket] runs on the capture thread and must return false when the transport is gone.
 * [enqueuePlayback] is safe from any thread and never blocks. [onError] runs on an audio thread
 * and must not block. All resources are released by [close].
 */
class XiaozhiAudio(
    private val context: Context,
    downlinkSampleRate: Int,
    private val sendPacket: (ByteArray) -> Boolean,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    private val codec = XiaozhiOpusCodec(downlinkSampleRate)
    private val rate = downlinkSampleRate
    private val closed = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(32)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null

    /** Opens the speaker path once. Safe to call repeatedly; ignores calls after [close]. */
    @Synchronized
    fun startPlayback() {
        if (closed.get() || !playing.compareAndSet(false, true)) return
        Thread({ playbackLoop() }, "xiaozhi-playback").start()
    }

    /** Starts one push-to-talk capture turn. Throws when the microphone permission is missing. */
    @Synchronized
    fun startCapture() {
        check(!closed.get()) { "Audio engine is closed" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission required"
        }
        if (!capturing.compareAndSet(false, true)) return
        Thread({ captureLoop() }, "xiaozhi-capture").start()
    }

    /** Ends the current capture turn. Playback and queued replies keep running. */
    fun stopCapture() {
        capturing.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
    }

    val isCapturing: Boolean get() = capturing.get() && !closed.get()

    /** Returns false on shutdown or backpressure; never blocks the WebSocket thread. */
    fun enqueuePlayback(packet: ByteArray): Boolean =
        !closed.get() && packet.isNotEmpty() && packet.size <= 3825 && queue.offer(packet.copyOf())

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var input: AudioRecord? = null
        try {
            if (!capturing.get()) return
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Microphone format unavailable" }
            input = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 3840)
            )
            check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            recorder = input
            if (!capturing.get()) return
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone start failed" }
            val frame = ShortArray(960)
            var offset = 0
            while (capturing.get()) {
                val n = input.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                // A stop during read returns a negative count; that is a normal turn end.
                if (n <= 0) { if (!capturing.get()) break; check(n < 0) { "Microphone read failed: $n" }; break }
                offset += n
                if (offset == frame.size) {
                    if (capturing.get() && !sendPacket(codec.encode(frame))) {
                        capturing.set(false)
                        throw IllegalStateException("Voice transport rejected audio")
                    }
                    offset = 0
                }
            }
        } catch (e: Exception) { failCapture(e) } finally {
            recorder = null
            try { input?.stop() } catch (_: Exception) { }
            try { input?.release() } catch (_: Exception) { }
        }
    }

    private fun playbackLoop() {
        var output: AudioTrack? = null
        try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Speaker format unavailable" }
            output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, rate * 240 / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            check(output.state == AudioTrack.STATE_INITIALIZED) { "Speaker initialization failed" }
            track = output
            if (closed.get()) return
            output.play()
            while (!closed.get()) {
                val packet = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val pcm = codec.decode(packet)
                var offset = 0
                while (!closed.get() && offset < pcm.size) {
                    val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) { if (closed.get()) break; check(written < 0) { "Speaker write failed: $written" }; break }
                    offset += written
                }
            }
        } catch (e: Exception) { failPlayback(e) } finally {
            track = null
            try { output?.pause(); output?.flush() } catch (_: Exception) { }
            try { output?.release() } catch (_: Exception) { }
        }
    }

    /** A capture failure ends the turn and is reported; it must not stop reply playback. */
    private fun failCapture(error: Exception) {
        if (!capturing.getAndSet(false) || closed.get()) return
        onError(error)
    }

    private fun failPlayback(error: Exception) {
        if (closed.getAndSet(true)) return
        playing.set(false)
        capturing.set(false)
        queue.clear()
        try { recorder?.stop() } catch (_: Exception) { }
        onError(error)
    }

    /** Discards queued audio and releases both paths. */
    @Synchronized
    override fun close() {
        if (closed.getAndSet(true)) return
        capturing.set(false)
        playing.set(false)
        queue.clear()
        try { recorder?.stop() } catch (_: Exception) { }
        try { track?.pause(); track?.flush() } catch (_: Exception) { }
    }
}
