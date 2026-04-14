package clawberry.aiworm.cn.asr

import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * FunASR 2-pass WebSocket ASR client.
 *
 * Connects to `wss://asr.aiworm.cn:443` (or a user-configured URL),
 * streams raw 16 kHz / 16-bit / mono PCM in the same framing used by
 * test_streaming.py, and returns the final transcription text.
 *
 * SSL certificate verification is intentionally skipped so the app can
 * connect even if the server uses a self-signed cert.
 */
object AsrClient {

    // ── ASR protocol constants ──────────────────────────────────────────────

    /** Chunk size spec matching the server default: [0, 10ms, 5 lookahead]. */
    private val CHUNK_SIZE = listOf(0, 10, 5)

    /** Pacing interval in milliseconds per chunk step. */
    private const val CHUNK_INTERVAL_MS = 10

    /**
     * Bytes per audio chunk:
     *   stride = 60 * CHUNK_SIZE[1] / CHUNK_INTERVAL / 1000 * 16000 Hz * 2 bytes
     *          = 60 * 10 / 10 / 1000 * 16000 * 2  = 1920 bytes  (≈ 60 ms of audio)
     */
    private const val AUDIO_STRIDE = 1920

    // ── HTTP client (shared, trust-all SSL) ─────────────────────────────────

    /**
     * Arbitrary non-zero tag used to label all OkHttp thread sockets so Android's
     * TrafficStats / StrictMode UntaggedSocketViolation is satisfied.
     */
    private const val SOCKET_TAG = 0x4153_5200  // 'ASR\0' in hex

    /**
     * Executor whose threads are pre-tagged with [SOCKET_TAG] so that every socket
     * OkHttp opens on these threads is properly attributed and StrictMode stays quiet.
     */
    private val taggedExecutor = Executors.newCachedThreadPool { runnable ->
        Thread {
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            try { runnable.run() } finally { TrafficStats.clearThreadStatsTag() }
        }.also { it.isDaemon = true }
    }

    private val trustAllClient: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .dispatcher(Dispatcher(taggedExecutor))   // ← tag every OkHttp socket thread
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Retry config ──────────────────────────────────────────────────────────
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2_000L

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Transcribe [pcmBytes] (16 kHz / 16-bit / mono raw PCM) using the
     * FunASR 2-pass server at [wsUrl].
     *
     * Retries up to [MAX_RETRIES] times on failure, waiting [RETRY_DELAY_MS] ms
     * between attempts.  Returns the final transcription, or `null` if all
     * attempts fail.
     *
     * Must be called from a coroutine context (e.g. `Dispatchers.IO`).
     */
    suspend fun transcribe(wsUrl: String, pcmBytes: ByteArray, mode: String = "2pass"): String? {
        repeat(MAX_RETRIES) { attempt ->
            android.util.Log.i("AsrClient", "transcribe attempt ${attempt + 1}/$MAX_RETRIES [mode=$mode] → $wsUrl")
            val result = transcribeOnce(wsUrl, pcmBytes, mode)
            if (result != null) return result
            if (attempt < MAX_RETRIES - 1) {
                android.util.Log.w("AsrClient", "attempt ${attempt + 1} returned null, retrying in ${RETRY_DELAY_MS}ms")
                delay(RETRY_DELAY_MS)
            }
        }
        android.util.Log.e("AsrClient", "all $MAX_RETRIES attempts failed")
        return null
    }

    private suspend fun transcribeOnce(wsUrl: String, pcmBytes: ByteArray, mode: String = "2pass"): String? =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(wsUrl).build()

            var ws: WebSocket? = null
            val partials = mutableListOf<String>()
            var finished = false

            fun finish(result: String?) {
                if (finished) return
                finished = true
                ws?.close(1000, "done")
                if (cont.isActive) cont.resume(result)
            }

            ws = trustAllClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    android.util.Log.i("AsrClient", "WS opened → ${wsUrl}, mode=$mode, pcm=${pcmBytes.size}B")
                    // 1. Send ASR config frame (matches test_streaming.py exactly)
                    val config = JSONObject().apply {
                        put("mode", mode)
                        put("chunk_size", org.json.JSONArray(CHUNK_SIZE))
                        put("chunk_interval", CHUNK_INTERVAL_MS)
                        put("encoder_chunk_look_back", 4)
                        put("decoder_chunk_look_back", 1)
                        put("wav_name", "clawberry_input")
                        put("wav_format", "pcm")   // ← tells server payload is raw PCM, not a file
                        put("itn", true)            // ← inverse text normalisation (numbers, punctuation)
                        put("hotwords", "")         // ← required field even if empty
                        put("is_speaking", true)
                    }
                    webSocket.send(config.toString())

                    // 2. Stream audio in chunks (no pacing needed post-recording)
                    var offset = 0
                    while (offset < pcmBytes.size) {
                        val end = minOf(offset + AUDIO_STRIDE, pcmBytes.size)
                        val chunk = pcmBytes.copyOfRange(offset, end).toByteString()
                        webSocket.send(chunk)
                        offset = end
                    }

                    // 3. Signal end-of-speech
                    webSocket.send(JSONObject().put("is_speaking", false).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        val content = msg.optString("text", "")
                        if (content.isBlank()) return

                        val mode = msg.optString("mode", "")
                        val isFinal = msg.optBoolean("is_final", false)

                        // 2pass server sends mode="2pass-offline" for the final result;
                        // standalone server sends mode="offline". Both end with "offline".
                        android.util.Log.d("AsrClient", "msg mode=$mode isFinal=$isFinal text=$content")
                        if (isFinal || mode.endsWith("offline")) {
                            android.util.Log.i("AsrClient", "FINAL result: $content")
                            finish(content)
                        } else {
                            // Online partial — keep last one as fallback
                            android.util.Log.d("AsrClient", "partial: $content")
                            partials.add(content)
                        }
                    } catch (_: Exception) {
                        // malformed JSON — ignore
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Server shouldn't send binary, but ignore gracefully
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // For "online" mode the server closes after streaming; last partial IS the result.
                    // For "offline"/"2pass" this is a fallback if the final message was missed.
                    val fallback = partials.lastOrNull()
                    android.util.Log.w("AsrClient", "WS closing ($code) — using '${fallback}' as result")
                    finish(fallback)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(partials.lastOrNull())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.e("AsrClient", "WS failure: ${t.message}")
                    finish(null)
                }
            })

            cont.invokeOnCancellation {
                ws?.cancel()
            }
        }
}
