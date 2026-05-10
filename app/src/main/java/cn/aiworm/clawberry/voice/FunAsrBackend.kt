package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [AsrBackend] implementation that streams raw PCM audio to a FunASR 2-pass WebSocket server
 * (the same service used by ZeroClaw and PicoClaw via [clawberry.aiworm.cn.asr.AsrClient]).
 *
 * **Audio pipeline:**
 * 1. [AudioRecord] captures 16 kHz / 16-bit / mono PCM continuously.
 * 2. Per utterance a WebSocket connection is opened to [wsUrl].
 * 3. A config frame is sent, followed by 60-ms PCM chunks.
 * 4. A simple energy-based VAD watches for [SILENCE_FINAL_MS] of quiet after detected speech,
 *    then sends `{"is_speaking": false}` to trigger the server's final decode.
 * 5. The server responds with a final result; [AsrCallbacks.onFinal] and [AsrCallbacks.onEndOfSpeech]
 *    are fired, and the caller re-opens a new session via [startListening].
 *
 * The [AudioRecord] is kept alive between utterances (only one instance ever exists) and
 * released only on [destroy].
 */
class FunAsrBackend(
    private val wsUrl: String,
    private val scope: CoroutineScope,
) : AsrBackend {

    companion object {
        // ── Protocol constants (matches AsrClient / test_streaming.py) ───────
        private val CHUNK_SIZE = listOf(0, 10, 5)
        private const val CHUNK_INTERVAL_MS = 10
        /**
         * bytes = 60 * CHUNK_SIZE[1] / CHUNK_INTERVAL / 1000 * 16000 * 2
         *       = 60 * 10 / 10 / 1000 * 16000 * 2 = 1920 bytes ≈ 60 ms
         */
        private const val AUDIO_STRIDE = 1920

        // ── VAD thresholds ───────────────────────────────────────────────────
        /** Normalised RMS level above which a frame is considered "speech". */
        private const val SILENCE_THRESHOLD = 0.04f
        /** Silence after detected speech → send is_speaking:false. */
        private const val SILENCE_FINAL_MS = 1_500L
        /** Session timeout when no speech is ever detected (like ERROR_SPEECH_TIMEOUT). */
        private const val SPEECH_TIMEOUT_MS = 8_000L
        /** Ignore local startup beep / recorder wake-up noise at the beginning of each session. */
        private const val STARTUP_IGNORE_MS = 450L

        // ── OkHttp ───────────────────────────────────────────────────────────
        private const val SOCKET_TAG = 0x4153_5201 // 'ASR\1'
    }

    private val httpClient: OkHttpClient by lazy { buildClient() }

    /**
     * Audio cue player — created on first use, released in [destroy].
     * Uses [AudioManager.STREAM_NOTIFICATION] so the beeps duck TTS audio cleanly.
     */
    private var toneGen: ToneGenerator? = null
    private fun toneGen() = toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME).also { toneGen = it }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var destroyed = false

    /** Kept alive across utterances; released only in [destroy]. */
    private var recorder: AudioRecord? = null

    /** Current utterance capture job. */
    private var sessionJob: Job? = null

    // ── AsrBackend ────────────────────────────────────────────────────────────

    override fun startListening(context: Context, callbacks: AsrCallbacks) {
        if (destroyed) return
        // Play the "ding" immediately — same timing as built-in ASR's onReadyForSpeech cue.
        // Recording starts in the coroutine; any frames captured before the WS handshake
        // completes are held in preBuffer and flushed once onOpen fires.
        toneGen().startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        // Cancel any in-flight session (shouldn't normally overlap, but be safe).
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
        val r = recorder
        recorder = null
        r?.stop()
        r?.release()
        toneGen?.release()
        toneGen = null
        httpClient.dispatcher.executorService.shutdownNow()
    }

    // ── AudioRecord lifecycle ─────────────────────────────────────────────────

    private fun ensureRecorder() {
        if (recorder != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, AUDIO_STRIDE * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return
        }
        r.startRecording()
        recorder = r
    }

    // ── Per-utterance session ─────────────────────────────────────────────────

    private suspend fun runSession(callbacks: AsrCallbacks) {
        val r = recorder ?: run {
            callbacks.onError(false, "ASR audio record unavailable")
            return
        }

        // Thread-safe flags: wsReady is written on OkHttp thread (onOpen) and read on IO coroutine.
        // sessionFinished guards against cascading onFailure calls after an intentional close.
        val wsReady = AtomicBoolean(false)
        val sessionFinished = AtomicBoolean(false)
        var isSpeakingSignalSent = false
        var speechDetected = false
        var lastActiveMs = System.currentTimeMillis()
        val sessionStartMs = System.currentTimeMillis()
        val startupIgnoreUntilMs = sessionStartMs + STARTUP_IGNORE_MS
        val partials = mutableListOf<String>()

        // Audio frames captured before the WS handshake are held here and flushed once
        // onOpen fires, so speech that starts immediately after the ding isn't lost.
        val preBufferLock = Any()
        val preBuffer = mutableListOf<ByteArray>()
        /** Max pre-WS frames to buffer (~3 s of audio at 60 ms/frame). */
        val maxPreFrames = 50

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = JSONObject().apply {
                    put("mode", "2pass")
                    put("chunk_size", JSONArray(CHUNK_SIZE.toMutableList()))
                    put("chunk_interval", CHUNK_INTERVAL_MS)
                    put("encoder_chunk_look_back", 4)
                    put("decoder_chunk_look_back", 1)
                    put("wav_name", "openclaw_voice")
                    put("wav_format", "pcm")
                    put("itn", true)
                    put("hotwords", "")
                    put("is_speaking", true)
                }
                webSocket.send(config.toString())
                // Mark ready and atomically drain the pre-buffer so the capture loop
                // doesn't add more frames between the drain and wsReady.set(true).
                val buffered = synchronized(preBufferLock) {
                    wsReady.set(true)
                    preBuffer.toList().also { preBuffer.clear() }
                }
                for (frame in buffered) webSocket.send(frame.toByteString())
                callbacks.onReady()
                Log.i("FunAsrBackend", "WS opened \u2192 $wsUrl (flushed ${buffered.size} pre-frames)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val content = msg.optString("text", "")
                    if (content.isBlank()) return
                    val mode = msg.optString("mode", "")
                    val isFinal = msg.optBoolean("is_final", false)
                    Log.d("FunAsrBackend", "msg mode=$mode isFinal=$isFinal text=$content")
                    if (isFinal || mode.endsWith("offline")) {
                        Log.i("FunAsrBackend", "FINAL \u2192 $content")
                        // "Ding-dong" — mirrors the built-in recognition-complete cue.
                        toneGen().startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                        // Mark finished BEFORE close so the resulting onFailure is suppressed.
                        sessionFinished.set(true)
                        callbacks.onFinal(content)
                        callbacks.onEndOfSpeech()
                        webSocket.close(1000, "done")
                    } else {
                        partials.add(content)
                        callbacks.onPartial(content)
                    }
                } catch (_: Exception) { }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Server closed before we got a final — fire fallback only if not already finished.
                if (sessionFinished.compareAndSet(false, true)) {
                    val fallback = partials.lastOrNull()
                    Log.w("FunAsrBackend", "WS closing ($code) fallback='$fallback'")
                    if (!fallback.isNullOrBlank()) callbacks.onFinal(fallback)
                    callbacks.onEndOfSpeech()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Suppress failures that happen AFTER an intentional close (ws.close / ws.cancel
                // in the finally block). Only report genuine mid-session failures.
                if (sessionFinished.get()) {
                    Log.d("FunAsrBackend", "WS closed (expected): ${t.message}")
                    return
                }
                Log.e("FunAsrBackend", "WS failure: ${t.message}")
                sessionFinished.set(true)
                callbacks.onError(false, "ASR connection failed", 1_200L)
            }
        }

        val request = Request.Builder().url(wsUrl).build()
        val ws = httpClient.newWebSocket(request, listener)

        val buf = ByteArray(AUDIO_STRIDE)
        try {
            // Also exit when sessionFinished is set externally (e.g. onFailure) so the
            // coroutine doesn't linger for up to SPEECH_TIMEOUT_MS reading silent audio.
            while (currentCoroutineContext().isActive && !destroyed && !sessionFinished.get()) {
                val read = r.read(buf, 0, buf.size)
                if (read <= 0) { delay(10); continue }

                val nowMs = System.currentTimeMillis()
                val inStartupIgnoreWindow = nowMs < startupIgnoreUntilMs

                val linearRms = computeLinearRms(buf, read)

                // Ring animation: emit 0 during silence so the ring drops immediately
                // (matching built-in ASR's _inputLevel = 0f in onEndOfSpeech).
                // During speech, use a log-scaled level that visually matches built-in ASR.
                val level = if (!inStartupIgnoreWindow && linearRms >= SILENCE_THRESHOLD) {
                    ((20f * log10(linearRms) + 60f) / 60f).coerceIn(0.05f, 1f)
                } else {
                    0f
                }
                callbacks.onRmsChanged(level)

                // If WS is not yet ready, buffer the frame; once onOpen fires it will
                // be flushed before live audio starts flowing.
                val ready = synchronized(preBufferLock) {
                    if (!wsReady.get()) {
                        if (!inStartupIgnoreWindow && preBuffer.size < maxPreFrames) {
                            preBuffer.add(buf.copyOf(read))
                        }
                        false
                    } else true
                }
                if (ready && !isSpeakingSignalSent && !inStartupIgnoreWindow) {
                    ws.send(buf.copyOf(read).toByteString())
                }

                if (!inStartupIgnoreWindow && linearRms >= SILENCE_THRESHOLD) {
                    speechDetected = true
                    lastActiveMs = nowMs
                }

                if (speechDetected) {
                    val silentMs = nowMs - lastActiveMs
                    if (silentMs >= SILENCE_FINAL_MS && !isSpeakingSignalSent && wsReady.get()) {
                        isSpeakingSignalSent = true
                        ws.send(JSONObject().put("is_speaking", false).toString())
                        Log.d("FunAsrBackend", "VAD: silence ${silentMs}ms \u2192 is_speaking=false")
                        // Wait for server final; onMessage sets sessionFinished=true so the
                        // ws.cancel() in finally is suppressed.
                        delay(5_000)
                        // If the server never replied with a final, fire onEndOfSpeech now so
                        // MicCaptureManager can scheduleRestart() and keep listening.
                        if (!sessionFinished.get()) {
                            callbacks.onEndOfSpeech()
                        }
                        break
                    }
                } else {
                    if (nowMs - sessionStartMs >= SPEECH_TIMEOUT_MS) {
                        Log.d("FunAsrBackend", "speech timeout \u2014 restarting session")
                        sessionFinished.set(true)
                        ws.close(1000, "timeout")
                        callbacks.onEndOfSpeech()
                        break
                    }
                }
            }
        } finally {
            // Always mark finished before cancel so any resulting onFailure is a no-op.
            sessionFinished.set(true)
            ws.cancel()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Compute linear RMS (0–1) from a 16-bit little-endian PCM buffer. */
    private fun computeLinearRms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val sample = (buf[i].toInt() and 0xFF or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += sample * sample
            i += 2
        }
        val count = len / 2
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun buildClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val taggedExecutor = Executors.newCachedThreadPool { r ->
            Thread {
                TrafficStats.setThreadStatsTag(SOCKET_TAG)
                try {
                    r.run()
                } finally {
                    TrafficStats.clearThreadStatsTag()
                }
            }.also { it.isDaemon = true }
        }
        return OkHttpClient.Builder()
            .dispatcher(Dispatcher(taggedExecutor))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
