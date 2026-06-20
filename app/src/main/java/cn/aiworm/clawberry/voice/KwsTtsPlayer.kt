package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
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
    private val ttsModel: TtsModel = TtsModel.DEFAULT,
    private val ttsSpeakerId: Int = 0,
) {
    private data class PhraseAudio(
        val samples: FloatArray,
        val sampleRate: Int,
    )

    private companion object {
        private const val TAG = "KwsTtsPlayer"
        private const val MAX_PHRASE_CACHE_SIZE = 64

        // Shared across all KwsTtsPlayer instances (Settings preview + runtime players).
        // Prevents repeated TTS generation for identical text.
        private val phraseCacheLock = Any()
        private val phraseCache = object : LinkedHashMap<String, PhraseAudio>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhraseAudio>): Boolean {
                return size > MAX_PHRASE_CACHE_SIZE
            }
        }
    }

    /** Composite cache key: model + speaker + text (prevents cross-model/sid pollution). */
    private fun cacheKey(text: String) = "${ttsModel.id}:$ttsSpeakerId:$text"

    private fun getCachedPhraseAudio(text: String): PhraseAudio? =
        synchronized(phraseCacheLock) { phraseCache[cacheKey(text)] }

    private fun putCachedPhraseAudio(text: String, audio: PhraseAudio) {
        synchronized(phraseCacheLock) { phraseCache[cacheKey(text)] = audio }
    }

    // ── Disk phrase cache ─────────────────────────────────────────────────────
    // Persists pre-rendered PCM across app restarts. On the first launch a phrase
    // is synthesised and written to filesDir/tts_phrase_cache/<sha1(text)>.bin;
    // subsequent launches load that file and skip TTS generation entirely.
    //
    // Binary format: [int sampleRate][int count][count × float samples]

    private val diskCacheDir: java.io.File by lazy {
        java.io.File(context.filesDir, "tts_phrase_cache").also { it.mkdirs() }
    }

    private fun phraseKey(text: String): String {
        val key = "${ttsModel.id}:$ttsSpeakerId:$text"
        val bytes = java.security.MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk(text: String): PhraseAudio? {
        return try {
            val file = java.io.File(diskCacheDir, "${phraseKey(text)}.bin")
            if (!file.exists()) return null
            java.io.DataInputStream(file.inputStream().buffered()).use { din ->
                val sampleRate = din.readInt()
                val count = din.readInt()
                val samples = FloatArray(count) { din.readFloat() }
                PhraseAudio(samples, sampleRate)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadFromDisk failed for '${text.take(20)}': ${e.message}")
            null
        }
    }

    private fun saveToDisk(text: String, audio: PhraseAudio) {
        try {
            val file = java.io.File(diskCacheDir, "${phraseKey(text)}.bin")
            java.io.DataOutputStream(file.outputStream().buffered()).use { dout ->
                dout.writeInt(audio.sampleRate)
                dout.writeInt(audio.samples.size)
                for (s in audio.samples) dout.writeFloat(s)
            }
            Log.d(TAG, "saved to disk '${text.take(20)}' (${audio.samples.size} samples @ ${audio.sampleRate} Hz)")
        } catch (e: Throwable) {
            Log.w(TAG, "saveToDisk failed for '${text.take(20)}': ${e.message}")
        }
    }

    /** Check memory cache first, then disk. Returns null only on a true miss. */
    private fun getOrLoadAudio(text: String): PhraseAudio? {
        getCachedPhraseAudio(text)?.let { return it }
        return loadFromDisk(text)?.also { putCachedPhraseAudio(text, it) }
    }

    private var tts: OfflineTts? = null
    // Completes (with engine, or null on failure) once init() loads the engine.
    private val engineDeferred = CompletableDeferred<OfflineTts?>()
    // OfflineTts.generate() is not thread-safe. All generate() calls must hold this lock.
    private val generateMutex = Mutex()
    // Ensures only one voice prompt plays at a time; the pending waiter re-checks the gen.
    private val promptPlayMutex = Mutex()
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
            getCachedPhraseAudio(newText)?.let { cached ->
                samples = cached.samples
                sampleRate = cached.sampleRate
                return
            }
            val engine = tts ?: return  // engine still loading — init() renders via [text]
            scope.launch(Dispatchers.IO) { renderWith(engine, newText) }
        }

        /** Render with a ready engine. Called sequentially from [init]. */
        suspend fun renderWith(engine: OfflineTts, forText: String = text) {
            try {
                getOrLoadAudio(forText)?.let { cached ->
                    if (text == forText) {
                        samples = cached.samples
                        sampleRate = cached.sampleRate
                        Log.d(TAG, "disk/cache hit '${forText.take(20)}' (${cached.samples.size} samples @ ${cached.sampleRate} Hz)")
                    }
                    return
                }
                val audio = generateMutex.withLock {
                    // Guard: release() sets tts=null inside this mutex, so if null here
                    // the engine was freed between launch() and this point — bail safely.
                    if (tts == null) return@withLock null
                    engine.generate(text = forText, sid = ttsSpeakerId, speed = 1.0f)
                } ?: return
                val phraseAudio = PhraseAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                putCachedPhraseAudio(forText, phraseAudio)
                saveToDisk(forText, phraseAudio)
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
            getOrLoadAudio(text)?.let { cached ->
                samples = cached.samples
                sampleRate = cached.sampleRate
                withContext(Dispatchers.IO) { playPcmFloat(cached.samples, cached.sampleRate) }
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
                        if (tts == null) return@withLock null
                        engine.generate(text = text, sid = ttsSpeakerId, speed = 1.0f)
                    } ?: return@withContext
                    val phraseAudio = PhraseAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                    putCachedPhraseAudio(text, phraseAudio)
                    saveToDisk(text, phraseAudio)
                    samples = audio.samples
                    sampleRate = audio.sampleRate
                    playPcmFloat(audio.samples, audio.sampleRate)
                } catch (e: Throwable) {
                    Log.w(TAG, "playSuspend fallback failed: ${e.message}")
                }
            }
        }

        /**
         * Like [playSuspend] but uses [playPromptPcmFloat] so it responds to [stopVoicePrompt].
         */
        suspend fun playSuspendInterruptible(gen: Int) {
            val s = samples
            if (s != null) {
                withContext(Dispatchers.IO) { playPromptPcmFloat(s, sampleRate, gen) }
                return
            }
            getOrLoadAudio(text)?.let { cached ->
                samples = cached.samples
                sampleRate = cached.sampleRate
                withContext(Dispatchers.IO) { playPromptPcmFloat(cached.samples, cached.sampleRate, gen) }
                return
            }
            val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() } ?: run {
                Log.w(TAG, "playSuspendInterruptible: engine not ready for '${text.take(20)}'")
                return
            }
            withContext(Dispatchers.IO) {
                try {
                    val audio = generateMutex.withLock {
                        if (tts == null) return@withLock null
                        engine.generate(text = text, sid = ttsSpeakerId, speed = 1.0f)
                    } ?: return@withContext
                    val phraseAudio = PhraseAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                    putCachedPhraseAudio(text, phraseAudio)
                    saveToDisk(text, phraseAudio)
                    samples = audio.samples
                    sampleRate = audio.sampleRate
                    playPromptPcmFloat(audio.samples, audio.sampleRate, gen)
                } catch (e: Throwable) {
                    Log.w(TAG, "playSuspendInterruptible fallback failed: ${e.message}")
                }
            }
        }
    }

    // Three pre-rendered phrases — texts are updated by the caller before/after init().
    private val greetingPhrase = CachedPhrase("你好主人")
    private val retryPhrase    = CachedPhrase("主人，请再说一遍你的指令")
    private val successPhrase  = CachedPhrase("主人，你的指令运行完毕")
    // Two interruptible voice-prompt phrases (thinking / tool-calls).
    private val thinkingPhrase   = CachedPhrase("让我考虑下如何完成任务")
    private val toolCallsPhrase  = CachedPhrase("任务在执行中，还需一些时间")

    // ── Interruptible prompt playback ────────────────────────────────────────

    /**
     * Incremented each time [stopVoicePrompt] is called. Each [playPromptPcmFloat] invocation
     * captures the generation at entry; the drain-wait loop exits as soon as the generation
     * changes — even if a new prompt immediately starts (which would previously have reset the
     * old shared [promptStopped] flag and let the old prompt keep playing).
     */
    private val promptGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /** The AudioTrack currently playing a voice prompt, or null. */
    @Volatile private var currentPromptTrack: AudioTrack? = null

    /** Like [playPcmFloat] but exits the drain-wait early when [stopVoicePrompt] is called. */
    private fun playPromptPcmFloat(samples: FloatArray, sampleRate: Int, gen: Int) {
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
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }
        // Already superseded before playback even started — discard
        if (promptGeneration.get() != gen) { track.release(); return }
        currentPromptTrack = track
        track.play()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        val durationMs = samples.size.toLong() * 1000L / sampleRate
        val deadline = System.currentTimeMillis() + durationMs + 300L
        while (System.currentTimeMillis() < deadline && promptGeneration.get() == gen) {
            Thread.sleep(50)
        }
        val interrupted = promptGeneration.get() != gen
        if (interrupted) {
            Log.d(TAG, "playPromptPcmFloat: interrupted by newer generation gen=$gen → ${promptGeneration.get()}")
        } else {
            Log.d(TAG, "playPromptPcmFloat: completed normally gen=$gen durationMs=$durationMs")
        }
        // Clear shared reference only if it still points to our track
        if (currentPromptTrack === track) currentPromptTrack = null
        // pause()+flush() discards buffered audio immediately; stop() after empty buffer is instant
        track.runCatching { pause(); flush(); stop(); release() }
    }

    /** Stop any voice-prompt playback that is currently in progress.
     *  Returns the new generation token — pass it to [playThinkingSuspend] or [playToolCallsSuspend]. */
    fun stopVoicePrompt(): Int {
        val gen = promptGeneration.incrementAndGet()
        // pause()+flush() silences immediately rather than letting the buffer drain
        currentPromptTrack?.runCatching { pause(); flush() }
        return gen
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load the TTS model and pre-render all cached phrases on the IO thread.
     * Safe to call multiple times — only the first call takes effect.
     */
    /**
     * Build the [OfflineTtsConfig] for [model] (defaults to the player's [ttsModel]).
     * Exposed with an explicit parameter so [init] can substitute a fallback model
     * when required asset files (e.g. the Matcha vocoder) are absent.
     */
    private fun buildTtsConfig(model: TtsModel = ttsModel, resolvedDataDir: String = ""): OfflineTtsConfig = when (model) {
        TtsModel.MatchaZhEn -> {
            val dir = model.id
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = "$dir/model-steps-3.onnx",
                        vocoder       = "$dir/vocos-16khz-univ.onnx",
                        lexicon       = "$dir/lexicon.txt",
                        tokens        = "$dir/tokens.txt",
                        dataDir       = resolvedDataDir,
                    ),
                ),
                ruleFsts = "$dir/phone-zh.fst,$dir/date-zh.fst,$dir/number-zh.fst",
            )
        }
        TtsModel.VitsZhLl -> {
            val dir = model.id
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model   = "$dir/model.onnx",
                        lexicon = "$dir/lexicon.txt",
                        tokens  = "$dir/tokens.txt",
                    ),
                ),
                ruleFsts = "$dir/phone.fst,$dir/date.fst,$dir/number.fst",
            )
        }
        TtsModel.VitsFanchenC -> {
            val dir = model.id
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model   = "$dir/vits-zh-hf-fanchen-C.onnx",
                        lexicon = "$dir/lexicon.txt",
                        tokens  = "$dir/tokens.txt",
                        dictDir = "$dir/dict",
                    ),
                ),
                ruleFsts = "$dir/phone.fst,$dir/date.fst,$dir/number.fst",
            )
        }
    }

    /**
     * Recursively extracts an asset directory to [Context.filesDir] and returns the
     * absolute filesystem path. sherpa-onnx's piper-phonemize lexicon reads espeak-ng-data
     * with direct file I/O — it cannot use the AssetManager, so the directory must live on
     * the real filesystem.
     *
     * Already-extracted files are skipped (existence check per-file) so subsequent launches
     * are fast.
     */
    private fun extractAssetDirToFiles(assetPath: String): String {
        val dest = java.io.File(context.filesDir, assetPath)
        copyAssetsRecursive(assetPath, dest)
        return dest.absolutePath
    }

    private fun copyAssetsRecursive(assetPath: String, destDir: java.io.File) {
        val children = context.assets.list(assetPath) ?: return
        destDir.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = java.io.File(destDir, child)
            val subChildren = context.assets.list(childAsset)
            if (!subChildren.isNullOrEmpty()) {
                copyAssetsRecursive(childAsset, childDest)
            } else {
                if (!childDest.exists()) {
                    context.assets.open(childAsset).use { input ->
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun init() {
        if (!initStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Initializing TTS engine (model=${ttsModel.id} sid=$ttsSpeakerId)…")
            try {
                // For MatchaZhEn the vocoder is a separate asset that is not bundled.
                // Loading OfflineTts without it causes a native crash that Kotlin cannot
                // catch, so we verify the file exists first and fall back to VitsZhLl.
                val effectiveModel = if (ttsModel == TtsModel.MatchaZhEn) {
                    val vocoderPath = "${ttsModel.id}/vocos-16khz-univ.onnx"
                    try {
                        context.assets.open(vocoderPath).close()
                        ttsModel
                    } catch (_: java.io.IOException) {
                        Log.w(TAG, "Matcha vocoder '$vocoderPath' not found in assets " +
                            "— falling back to ${TtsModel.VitsZhLl.id}. " +
                            "Download vocos-16khz-univ.onnx and place it in assets/${ttsModel.id}/ to enable Matcha.")
                        TtsModel.VitsZhLl
                    }
                } else ttsModel
                // espeak-ng-data must be on the real filesystem — native piper-phonemize
                // opens it with plain file I/O and cannot go through the AssetManager.
                val effectiveDataDir = if (effectiveModel == TtsModel.MatchaZhEn) {
                    extractAssetDirToFiles("${effectiveModel.id}/espeak-ng-data")
                } else ""
                val config = buildTtsConfig(effectiveModel, effectiveDataDir)
                val engine = OfflineTts(assetManager = context.assets, config = config)
                tts = engine
                // Pre-render all phrases sequentially (generateMutex held inside each)
                greetingPhrase.renderWith(engine)
                retryPhrase.renderWith(engine)
                successPhrase.renderWith(engine)
                thinkingPhrase.renderWith(engine)
                toolCallsPhrase.renderWith(engine)
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
    fun updateGreeting(text: String)        = greetingPhrase.update(text)
    /** Update the retry phrase (full text, e.g. "主人，请再说一遍你的指令"). */
    fun updateRetryPhrase(text: String)     = retryPhrase.update(text)
    /** Update the success phrase (full text, e.g. "主人，你的指令运行完毕"). */
    fun updateSuccessPhrase(text: String)   = successPhrase.update(text)
    /** Update the thinking-filler phrase. */
    fun updateThinkingPhrase(text: String)  = thinkingPhrase.update(text)
    /** Update the tool-calls-filler phrase. */
    fun updateToolCallsPhrase(text: String) = toolCallsPhrase.update(text)

    // ── Playback API ──────────────────────────────────────────────────────────

    /**
     * Play the cached greeting asynchronously.
     * [onFinished] is invoked on the IO thread once playback completes (or immediately if the
     * engine is not ready yet). Use it to defer work that must happen *after* the greeting audio
     * is fully done — e.g. opening the ASR microphone so it cannot pick up the greeting echo.
     *
     * Holds [promptPlayMutex] during playback so voice prompts (thinking / tool-calls) that
     * arrive before the greeting finishes are queued rather than played concurrently.
     */
    fun playGreeting(onFinished: (() -> Unit)? = null) {
        val s = greetingPhrase.samples
        if (s == null) {
            Log.w(TAG, "playGreeting: not cached yet — skipping")
            onFinished?.invoke()
            return
        }
        val rate = greetingPhrase.sampleRate
        scope.launch(Dispatchers.IO) {
            promptPlayMutex.withLock { playPcmFloat(s, rate) }
            onFinished?.invoke()
        }
    }

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
     * Suspend until the thinking-filler phrase finishes, or until a newer prompt supersedes it.
     * [gen] must be the value returned by [stopVoicePrompt] immediately before this call.
     * The [promptPlayMutex] ensures only one prompt plays at a time; the gen check after
     * acquiring the lock guarantees the latest-requested prompt always wins.
     */
    suspend fun playThinkingSuspend(gen: Int) {
        promptPlayMutex.withLock {
            // yield() does two things:
            // 1. Calls ensureActive() — throws CancellationException if the job was cancel()-ed
            //    before/during Mutex.tryLock() (which has no built-in cancellation check).
            // 2. Re-dispatches — gives the IO thread pool one scheduler cycle so any pending
            //    stopVoicePrompt() increment on the WebSocket thread is visible before we check gen.
            yield()
            if (promptGeneration.get() == gen) thinkingPhrase.playSuspendInterruptible(gen)
        }
    }

    /**
     * Suspend until the tool-calls-filler phrase finishes, or until a newer prompt supersedes it.
     */
    suspend fun playToolCallsSuspend(gen: Int) {
        promptPlayMutex.withLock {
            yield()
            if (promptGeneration.get() == gen) toolCallsPhrase.playSuspendInterruptible(gen)
        }
    }

    /**
     * Generate and play [text] on the IO thread.
     * Used for the settings preview button — bypasses the cache so the user hears
     * whatever is currently typed, even before saving.
     * Waits up to 15 s for the engine to finish loading before giving up.
     */
    fun playText(text: String) {
        scope.launch(Dispatchers.IO) {
            getOrLoadAudio(text)?.let { cached ->
                playPcmFloat(cached.samples, cached.sampleRate)
                return@launch
            }
            val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() }
            if (engine == null) {
                Log.w(TAG, "playText: TTS engine not ready — skipping preview")
                return@launch
            }
            try {
                val audio = generateMutex.withLock {
                    if (tts == null) return@withLock null
                    engine.generate(text = text, sid = ttsSpeakerId, speed = 1.0f)
                } ?: return@launch
                val phraseAudio = PhraseAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                putCachedPhraseAudio(text, phraseAudio)
                saveToDisk(text, phraseAudio)
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
        getOrLoadAudio(text)?.let { cached ->
            withContext(Dispatchers.IO) { playPcmFloat(cached.samples, cached.sampleRate) }
            return
        }
        val engine = withTimeoutOrNull(15_000L) { engineDeferred.await() }
        if (engine == null) {
            Log.w(TAG, "playTextSuspend: TTS engine not ready — skipping")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val audio = generateMutex.withLock {
                    if (tts == null) return@withLock null
                    engine.generate(text = text, sid = ttsSpeakerId, speed = 1.0f)
                } ?: return@withContext
                val phraseAudio = PhraseAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                putCachedPhraseAudio(text, phraseAudio)
                saveToDisk(text, phraseAudio)
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
        Log.d(TAG, "playPcmFloat: playback complete")
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
