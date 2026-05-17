package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a short TTS greeting ("你好主人") when the KWS keyword is detected.
 *
 * The sherpa-onnx VITS model is loaded once on the IO thread; the greeting audio is
 * pre-rendered and cached so subsequent plays are near-instant.
 *
 * **Setup**: place the model directory `sherpa-onnx-vits-zh-ll` under
 * `app/src/main/assets/`.  Download from:
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
 *   → sherpa-onnx-vits-zh-ll.tar.bz2
 *
 * Required asset files:
 *   assets/sherpa-onnx-vits-zh-ll/model.onnx
 *   assets/sherpa-onnx-vits-zh-ll/lexicon.txt
 *   assets/sherpa-onnx-vits-zh-ll/tokens.txt
 *   assets/sherpa-onnx-vits-zh-ll/phone.fst
 *   assets/sherpa-onnx-vits-zh-ll/date.fst
 *   assets/sherpa-onnx-vits-zh-ll/number.fst
 *
 * Call [init] at startup, [playGreeting] on keyword detection, [release] on cleanup.
 */
class KwsTtsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        private const val TAG = "KwsTtsPlayer"
        private const val MODEL_DIR  = "sherpa-onnx-vits-zh-ll"
        private const val MODEL_FILE = "$MODEL_DIR/model.onnx"
        private const val LEXICON    = "$MODEL_DIR/lexicon.txt"
        private const val TOKENS     = "$MODEL_DIR/tokens.txt"
        private const val RULE_FSTS  = "$MODEL_DIR/phone.fst,$MODEL_DIR/date.fst,$MODEL_DIR/number.fst"
    }

    private var tts: OfflineTts? = null
    // Completes (with engine, or null on failure) once init() loads the engine.
    private val engineDeferred = CompletableDeferred<OfflineTts?>()
    // OfflineTts.generate() is not thread-safe. All generate() calls must hold this lock.
    private val generateMutex = Mutex()
    // Prevents duplicate init() launches.
    private val initStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Pre-rendered phrase cache ─────────────────────────────────────────────

    /**
     * A single TTS phrase that is pre-rendered at startup and played from cache.
     * Thread-safe for concurrent [update] and [playSuspend] calls.
     */
    private inner class CachedPhrase(initialText: String) {
        @Volatile var text: String = initialText
        @Volatile var samples: FloatArray? = null
        @Volatile var sampleRate: Int = 22050

        /** Re-render if [newText] differs; no-op if already cached for that text. */
        fun update(newText: String) {
            if (newText == text && samples != null) return
            text = newText
            samples = null
            val engine = tts ?: return  // engine still loading — init() renders via [text]
            scope.launch(Dispatchers.IO) { renderWith(engine, newText) }
        }

        /** Render with a ready engine. Called sequentially from [init]. */
        suspend fun renderWith(engine: OfflineTts, forText: String = text) {
            try {
                val audio = generateMutex.withLock {
                    engine.generate(text = forText, sid = 0, speed = 1.0f)
                }
                if (text == forText) {
                    samples    = audio.samples
                    sampleRate = audio.sampleRate
                    Log.d(TAG, "pre-rendered '${forText.take(20)}' (${audio.samples.size} samples @ ${audio.sampleRate} Hz)")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "renderWith failed for '${forText.take(20)}': ${e.message}")
            }
        }

        /**
         * Play asynchronously on IO.
         * [onFinished] is called on the IO thread when done (or immediately if not cached).
         */
        fun playAsync(onFinished: (() -> Unit)? = null) {
            val s = samples
            if (s == null) {
                Log.w(TAG, "playAsync: '${text.take(20)}' not cached yet — skipping")
                onFinished?.invoke()
                return
            }
            val rate = sampleRate
            scope.launch(Dispatchers.IO) {
                playPcmFloat(s, rate)
                onFinished?.invoke()
            }
        }

        /**
         * Suspend until playback completes.
         * Uses cached samples if available; falls back to live generation otherwise.
         */
        suspend fun playSuspend() {
            val s = samples
            if (s != null) {
                withContext(Dispatchers.IO) { playPcmFloat(s, sampleRate) }
                return
            }
            // Cache miss — fall back to on-demand generation
            val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() } ?: run {
                Log.w(TAG, "playSuspend: engine not ready for '${text.take(20)}'")
                return
            }
            withContext(Dispatchers.IO) {
                try {
                    val audio = generateMutex.withLock {
                        engine.generate(text = text, sid = 0, speed = 1.0f)
                    }
                    playPcmFloat(audio.samples, audio.sampleRate)
                } catch (e: Throwable) {
                    Log.w(TAG, "playSuspend fallback failed: ${e.message}")
                }
            }
        }
    }

    // Three pre-rendered phrases — texts are updated by the caller before/after init().
    private val greetingPhrase = CachedPhrase("你好主人")
    private val retryPhrase    = CachedPhrase("主人，请再说一遍你的指令")
    private val successPhrase  = CachedPhrase("主人，你的指令运行完毕")

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load the TTS model and pre-render all cached phrases on the IO thread.
     * Safe to call multiple times — only the first call takes effect.
     */
    fun init() {
        if (!initStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Initializing TTS engine…")
            try {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model   = MODEL_FILE,
                            lexicon = LEXICON,
                            tokens  = TOKENS,
                        ),
                    ),
                    ruleFsts = RULE_FSTS,
                )
                val engine = OfflineTts(assetManager = context.assets, config = config)
                tts = engine
                // Pre-render all phrases sequentially (generateMutex held inside each)
                greetingPhrase.renderWith(engine)
                retryPhrase.renderWith(engine)
                successPhrase.renderWith(engine)
                // Complete deferred AFTER all pre-renders so playTextSuspend callers don't
                // race generate() calls that are still in progress.
                engineDeferred.complete(engine)
                Log.d(TAG, "Ready — all phrases pre-rendered")
            } catch (e: Throwable) {
                Log.w(TAG, "Init failed — KWS TTS will be silent: ${e.message}")
                engineDeferred.complete(null)
            }
        }
    }

    // ── Phrase update API ─────────────────────────────────────────────────────

    /** Update the greeting phrase (full text, e.g. "你好主人"). */
    fun updateGreeting(text: String)      = greetingPhrase.update(text)
    /** Update the retry phrase (full text, e.g. "主人，请再说一遍你的指令"). */
    fun updateRetryPhrase(text: String)   = retryPhrase.update(text)
    /** Update the success phrase (full text, e.g. "主人，你的指令运行完毕"). */
    fun updateSuccessPhrase(text: String) = successPhrase.update(text)

    // ── Playback API ──────────────────────────────────────────────────────────

    /**
     * Play the cached greeting asynchronously.
     * [onFinished] is invoked on the IO thread once playback completes (or immediately if the
     * engine is not ready yet). Use it to defer work that must happen *after* the greeting audio
     * is fully done — e.g. opening the ASR microphone so it cannot pick up the greeting echo.
     */
    fun playGreeting(onFinished: (() -> Unit)? = null) = greetingPhrase.playAsync(onFinished)

    /**
     * Suspend until the cached retry phrase ("主人，请再说一遍你的指令") finishes playing.
     * Falls back to live generation if the cache is not ready.
     */
    suspend fun playRetrySuspend() = retryPhrase.playSuspend()

    /**
     * Suspend until the cached success phrase ("主人，你的指令运行完毕") finishes playing.
     * Falls back to live generation if the cache is not ready.
     */
    suspend fun playSuccessSuspend() = successPhrase.playSuspend()

    /**
     * Generate and play [text] on the IO thread.
     * Used for the settings preview button — bypasses the cache so the user hears
     * whatever is currently typed, even before saving.
     * Waits up to 15 s for the engine to finish loading before giving up.
     */
    fun playText(text: String) {
        scope.launch(Dispatchers.IO) {
            val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() }
            if (engine == null) {
                Log.w(TAG, "playText: TTS engine not ready — skipping preview")
                return@launch
            }
            try {
                val audio = generateMutex.withLock { engine.generate(text = text, sid = 0, speed = 1.0f) }
                playPcmFloat(audio.samples, audio.sampleRate)
            } catch (e: Throwable) {
                Log.w(TAG, "playText failed: ${e.message}")
            }
        }
    }

    /**
     * Suspending variant of [playText]: generates and plays [text], then suspends until
     * playback is fully complete.  Waits up to 15 s for the TTS engine to be ready.
     * Safe to call from any coroutine context — heavy work runs on IO.
     */
    suspend fun playTextSuspend(text: String) {
        val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() }
        if (engine == null) {
            Log.w(TAG, "playTextSuspend: TTS engine not ready — skipping")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val audio = generateMutex.withLock { engine.generate(text = text, sid = 0, speed = 1.0f) }
                playPcmFloat(audio.samples, audio.sampleRate)
            } catch (e: Throwable) {
                Log.w(TAG, "playTextSuspend failed: ${e.message}")
            }
        }
    }

    private fun playPcmFloat(samples: FloatArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(samples.size * 4)

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        val track = AudioTrack(
            attr, format, minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            track.release()
            return
        }
        track.play()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        // WRITE_BLOCKING returns once data is in the buffer, not when it finishes playing.
        // Wait for actual playback to drain before stop()+release() discard buffered audio.
        val durationMs = samples.size.toLong() * 1000L / sampleRate
        Thread.sleep(durationMs + 300L)
        track.stop()
        track.release()
        Log.d(TAG, "playGreeting: playback complete")
    }

    /** Release native TTS resources. Waits for any in-progress generate() call to finish
     *  before freeing the native object to prevent use-after-free crashes in libonnxruntime. */
    fun release() {
        kotlinx.coroutines.runBlocking {
            generateMutex.withLock {
                tts?.release()
                tts = null
            }
        }
    }
}
