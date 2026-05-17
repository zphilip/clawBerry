package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [AsrBackend] that captures audio locally, applies energy-based VAD to detect utterance
 * boundaries, then sends the WAV to the FunASR speaker-gated transcription endpoint:
 *
 *   POST [BASE_URL]/api/v1/asr/transcribe/targeted
 *     multipart: user_id, threshold, audio (WAV)
 *
 * If the speaker is matched, [AsrCallbacks.onFinal] is called with the transcript text.
 * If the speaker is NOT matched (wrong person), [AsrCallbacks.onFinal] is called with an
 * empty string — [MicCaptureManager.queueRecognizedMessage] silently drops empty strings, so
 * the conversation is not affected.
 *
 * The [AudioRecord] is kept alive between utterances; only [destroy] releases it.
 */
class FunAsrIdentityBackend(
    private val userId: String,
    private val scope: CoroutineScope,
    private val threshold: () -> Float = { DEFAULT_THRESHOLD },
    /**
     * How long to wait for the first speech energy before giving up and calling
     * [AsrCallbacks.onEndOfSpeech].  Pass a shorter value for KWS-triggered sessions
     * where the user is expected to speak immediately after the wake word.
     */
    private val speechTimeoutMs: Long = SPEECH_TIMEOUT_MS,
) : AsrBackend {

    companion object {
        const val BASE_URL = "http://apicn.aiworm.cn:8811"
        const val DEFAULT_THRESHOLD = 0.45f
        /** Default no-speech timeout; use a shorter value for KWS sessions. */
        const val SPEECH_TIMEOUT_MS = 20_000L

        private const val TAG = "FunAsrIdentityBackend"
        private const val SAMPLE_RATE = 16_000
        // 60 ms of audio per chunk (same stride as FunAsrBackend)
        private const val AUDIO_STRIDE = 1_920

        // Energy-based VAD thresholds — copied from FunAsrBackend
        private const val SILENCE_THRESHOLD = 200.0      // linear RMS
        private const val SILENCE_FINAL_MS = 1_500L      // ms of silence → end of utterance
        private const val STARTUP_IGNORE_MS = 400L       // ignore energy bursts right after start
    }

    @Volatile private var destroyed = false
    private var recorder: AudioRecord? = null
    private var sessionJob: Job? = null
    private var toneGen: ToneGenerator? = null

    // Lazy so OkHttpClient (which calls SSLContext.init) is never built on the main thread.
    // Building it eagerly in the constructor causes a StrictMode CustomViolation: newSSLContext.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)   // fail fast when server is down — KWS pauses during this window
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── AsrBackend ────────────────────────────────────────────────────────────

    override fun startListening(context: Context, callbacks: AsrCallbacks) {
        if (destroyed) return
        toneOf().startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            ensureRecorder()
            runSession(callbacks)
        }
    }

    override fun destroy() {
        destroyed = true
        sessionJob?.cancel()
        sessionJob = null
        val r = recorder; recorder = null
        r?.stop(); r?.release()
        toneGen?.release(); toneGen = null
        http.dispatcher.executorService.shutdownNow()
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun ensureRecorder() {
        // Recreate if null, not initialized, or not actively recording.
        val existing = recorder
        if (existing != null &&
            existing.state == AudioRecord.STATE_INITIALIZED &&
            existing.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return
        }
        // Release stale recorder before creating a new one.
        if (existing != null) {
            Log.w(TAG, "Recreating AudioRecord (state=${existing.state} recordingState=${existing.recordingState})")
            try { existing.stop() } catch (_: Throwable) {}
            existing.release()
            recorder = null
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, AUDIO_STRIDE * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); Log.e(TAG, "AudioRecord init failed"); return }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); Log.e(TAG, "AudioRecord startRecording failed"); return
        }
        Log.d(TAG, "AudioRecord created and started")
        recorder = r
    }

    private suspend fun runSession(callbacks: AsrCallbacks) {
        val r = recorder ?: run {
            callbacks.onError(false, "ASR-ID audio record unavailable")
            return
        }

        callbacks.onReady()

        val pcmBuffer = ByteArrayOutputStream()
        val chunk = ByteArray(AUDIO_STRIDE)
        var speechDetected = false
        var lastActiveMs = System.currentTimeMillis()
        val sessionStartMs = System.currentTimeMillis()
        val startupIgnoreUntilMs = sessionStartMs + STARTUP_IGNORE_MS
        var consecutiveReadErrors = 0

        while (currentCoroutineContext().isActive && !destroyed) {
            val read = r.read(chunk, 0, chunk.size)
            if (read <= 0) {
                consecutiveReadErrors++
                if (consecutiveReadErrors == 1) {
                    Log.w(TAG, "AudioRecord.read returned $read (error), will recreate after 500ms of errors")
                }
                if (consecutiveReadErrors >= 50) {
                    // AudioRecord is stuck (e.g. lost audio focus after TTS). Recreate and retry.
                    Log.w(TAG, "AudioRecord stuck for ${consecutiveReadErrors * 10}ms — recreating")
                    try { r.stop() } catch (_: Throwable) {}
                    r.release()
                    recorder = null
                    delay(200)
                    if (destroyed || !currentCoroutineContext().isActive) return
                    ensureRecorder()
                    val newR = recorder
                    if (newR == null) {
                        Log.e(TAG, "Recorder recreate failed — signalling error")
                        callbacks.onError(false, "ASR-ID recorder failed", 1_000L)
                        return
                    }
                    // runSession will be restarted by scheduleRestart; end this session.
                    Log.d(TAG, "Recorder recreated — ending session to trigger restart")
                    callbacks.onEndOfSpeech()
                    return
                }
                delay(10)
                continue
            }
            consecutiveReadErrors = 0

            pcmBuffer.write(chunk, 0, read)

            val nowMs = System.currentTimeMillis()
            val inStartup = nowMs < startupIgnoreUntilMs
            val rms = computeLinearRms(chunk, read)

            // Animate ring
            val level = if (!inStartup && rms >= SILENCE_THRESHOLD) {
                ((20.0 * log10(rms) + 60.0) / 60.0).toFloat().coerceIn(0.05f, 1f)
            } else 0f
            callbacks.onRmsChanged(level)

            if (!inStartup && rms >= SILENCE_THRESHOLD) {
                speechDetected = true
                lastActiveMs = nowMs
            }

            // Abandon if no speech heard within timeout
            if (!speechDetected && (nowMs - sessionStartMs) > speechTimeoutMs) {
                Log.d(TAG, "No speech detected — ending session")
                callbacks.onEndOfSpeech()
                return
            }

            // End of utterance: speech was detected and silence has persisted
            if (speechDetected && (nowMs - lastActiveMs) >= SILENCE_FINAL_MS) {
                Log.d(TAG, "VAD: silence ${nowMs - lastActiveMs}ms → sending to identity ASR")
                break
            }
        }

        if (!currentCoroutineContext().isActive || destroyed) return

        // Upload to server
        toneOf().startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        val pcm = pcmBuffer.toByteArray()
        val wav = pcmToWav(pcm, SAMPLE_RATE)

        val transcript = runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("user_id", userId)
                .addFormDataPart("threshold", threshold().toString())
                .addFormDataPart("audio", "utterance.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val req = okhttp3.Request.Builder()
                .url("$BASE_URL/api/v1/asr/transcribe/targeted")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Server returned ${resp.code}: $bodyStr")
                    return@use ""
                }
                val json = JSONObject(bodyStr.ifEmpty { "{}" })
                val matched = json.optBoolean("speaker_matched", false)
                val text = json.optString("text", "").trim()
                val similarity = json.optDouble("similarity", 0.0)
                val threshold = json.optDouble("threshold", 0.0)
                Log.i(TAG, "identity ASR: matched=$matched similarity=%.4f threshold=%.4f text='$text'".format(similarity, threshold))
                if (matched) text else ""
            }
        }.getOrElse { e ->
            Log.e(TAG, "identity ASR upload failed: ${e::class.simpleName}: ${e.message}")
            ""
        }

        // Guard: if destroy() was called while the HTTP request was in-flight (e.g. TTS
        // started), skip callbacks to prevent spurious scheduleRestart() from firing on
        // the old backend after the new backend has already taken over.
        if (destroyed || !currentCoroutineContext().isActive) {
            Log.d(TAG, "session ending post-destroy — suppressing callbacks")
            return
        }

        callbacks.onFinal(transcript)
        callbacks.onEndOfSpeech()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun toneOf() = toneGen
        ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
            .also { toneGen = it }

    private fun computeLinearRms(buf: ByteArray, len: Int): Double {
        var sumSq = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sumSq += s.toDouble() * s
            i += 2
        }
        return sqrt(sumSq / (len / 2).coerceAtLeast(1))
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1; val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        val dos = DataOutputStream(out)
        fun i32(v: Int) = dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun i16(v: Int) = dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        dos.writeBytes("RIFF"); i32(36 + pcm.size); dos.writeBytes("WAVEfmt "); i32(16)
        i16(1); i16(channels); i32(sampleRate); i32(byteRate); i16(blockAlign); i16(bitsPerSample)
        dos.writeBytes("data"); i32(pcm.size); dos.write(pcm)
        return out.toByteArray()
    }
}
