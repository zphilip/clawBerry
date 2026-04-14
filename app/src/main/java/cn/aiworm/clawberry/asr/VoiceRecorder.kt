package clawberry.aiworm.cn.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Push-to-talk microphone recorder.
 *
 * [start] opens [AudioRecord] and immediately spins up a daemon thread that
 * continuously drains the hardware ring buffer into an internal
 * [ByteArrayOutputStream].  Without this continuous read the ring buffer fills
 * within milliseconds and all subsequent audio is silently discarded.
 *
 * [stop] signals the read thread to exit, waits up to 2 s for it to finish,
 * and returns the raw 16 kHz / 16-bit / mono PCM bytes collected so far.
 *
 * Call [start] from any thread (it returns immediately).
 * Call [stop] from a background thread / IO dispatcher (it blocks briefly on join).
 *
 * Requires the RECORD_AUDIO permission to be granted before calling [start].
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000              // Hz
        const val CHANNEL    = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING   = AudioFormat.ENCODING_PCM_16BIT

        /** 100 ms of audio per read — keeps latency low while minimising syscall overhead. */
        private const val READ_CHUNK = 3_200        // bytes: 16000 Hz × 2 B × 0.1 s

        /**
         * Returns true if [pcm] is too short or too quiet to be worth transcribing.
         *
         * [minBytes] = 16 000 → 0.5 s minimum at 16 kHz / 16-bit mono.
         * [rmsThreshold] = 300 out of a full-scale of 32 767 (≈ 1 % FS).
         */
        fun isSilent(
            pcm: ByteArray,
            minBytes: Int = 16_000,
            rmsThreshold: Double = 300.0,
        ): Boolean {
            if (pcm.size < minBytes) return true
            var sumSq = 0.0
            var i = 0
            while (i < pcm.size - 1) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                sumSq += s.toDouble() * s
                i += 2
            }
            return kotlin.math.sqrt(sumSq / (pcm.size / 2)) < rmsThreshold
        }
    }

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var running   = false
    private val buffer     = ByteArrayOutputStream()
    private var readThread: Thread? = null

    val isRecording: Boolean get() = running

    /**
     * Opens the microphone and starts a background read loop.
     * Returns immediately; audio accumulates in memory until [stop] is called.
     */
    fun start() {
        if (running) return
        buffer.reset()

        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // Give the hardware ring buffer room for at least 4 × our read chunk
        val hwBuf   = maxOf(minBuf, READ_CHUNK * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, hwBuf,
        )
        recorder = rec
        rec.startRecording()
        running = true

        readThread = Thread {
            val chunk = ByteArray(READ_CHUNK)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Stops recording and returns all accumulated PCM bytes.
     *
     * Blocks up to 2 s waiting for the read thread to drain cleanly.
     * Safe to call from a coroutine on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun stop(): ByteArray {
        running = false
        readThread?.join(2_000)
        readThread = null
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        return synchronized(buffer) { buffer.toByteArray() }
    }

    /** Release all resources without returning data (e.g. on composable disposal). */
    fun release() {
        running = false
        readThread?.interrupt()
        readThread = null
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        synchronized(buffer) { buffer.reset() }
    }
}
