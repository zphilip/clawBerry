package clawberry.aiworm.cn.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Always-on keyword spotter using Sherpa-ONNX.
 *
 * Uses the `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` model stored in
 * `app/src/main/assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/`.
 *
 * When a keyword is detected, [onKeyword] is invoked on the calling coroutine
 * context (IO thread). The caller is responsible for switching to the appropriate
 * thread/scope for UI updates.
 *
 * Call [start] to begin listening, [stop] to stop. The manager owns its own
 * [AudioRecord] which runs **independently** of [MicCaptureManager] — both can
 * coexist because both are read-only consumers of the microphone.
 *
 * **Important**: model ONNX files must be placed in the assets directory before
 * this will work. See the README.md in the model assets directory.
 */
class KwsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onKeyword: (keyword: String) -> Unit,
) {
    companion object {
        private const val TAG = "KwsManager"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"

        // Number of PCM samples per chunk fed to the spotter (160ms at 16kHz with chunk-16 model)
        private const val CHUNK_SAMPLES = 2560  // 160ms = 0.160s * 16000

        // Minimum ms between successive keyword triggers (debounce)
        private const val KEYWORD_COOLDOWN_MS = 3_000L

        // Log audio-flow stats every N chunks (200 * 160ms ≈ 32s)
        private const val LOG_INTERVAL_CHUNKS = 200L

        private const val STREAM_RESET_CHUNKS = 300L  // unused — periodic reset removed
    }

    @Volatile private var running = false
    private var captureJob: Job? = null
    private var spotter: KeywordSpotter? = null

    /** True while the KWS is actively listening for keywords. */
    val isRunning: Boolean get() = running

    /**
     * Start keyword spotting. No-op if already running.
     * Requires RECORD_AUDIO permission — returns false if missing.
     * [buildSpotter] runs lazily on the IO thread to avoid blocking the main thread.
     */
    fun start(): Boolean {
        if (running) {
            Log.d(TAG, "start: already running — no-op")
            return true
        }
        Log.d(TAG, "start called")
        if (!hasMicPermission()) {
            Log.w(TAG, "start: RECORD_AUDIO permission not granted")
            return false
        }
        if (!modelFilesExist()) {
            Log.w(TAG, "start: KWS model files not found in assets/$MODEL_DIR — skipping KWS")
            return false
        }
        running = true
        captureJob = scope.launch(Dispatchers.IO) {
            runCaptureLoop()
        }
        Log.i(TAG, "started")
        return true
    }

    /** Stop keyword spotting and release all resources including the ONNX model. */
    fun stop() {
        running = false
        val jobToAwait = captureJob
        captureJob = null
        val spotterToRelease = spotter
        spotter = null
        // Defer native release until after the capture coroutine exits.
        // Calling spotter.release() while the IO thread is still inside kws.isReady()
        // causes a use-after-free SIGSEGV — coroutine cancellation is cooperative and
        // won't preempt a blocking native call.
        scope.launch(Dispatchers.IO) {
            jobToAwait?.join()
            spotterToRelease?.release()
        }
        Log.i(TAG, "stopped")
    }

    /** Temporarily pause (stop AudioRecord) without destroying the spotter state.
     *  Returns the cancelled [Job] so the caller can [Job.join] to wait until the
     *  AudioRecord is fully released before opening a new one. */
    fun pause(): kotlinx.coroutines.Job? {
        if (!running) {
            Log.d(TAG, "pause: already not running — no-op")
            return null
        }
        running = false
        val job = captureJob
        captureJob?.cancel()
        captureJob = null
        return job  // join this to await AudioRecord release
    }

    /** Resume after [pause], reusing the existing loaded spotter (no ONNX reload). */
    fun resume(): Boolean {
        if (running) {
            Log.d(TAG, "resume: already running — no-op")
            return true
        }
        Log.d(TAG, "resume called")
        if (!hasMicPermission()) {
            Log.w(TAG, "resume: RECORD_AUDIO permission not granted")
            return false
        }
        // spotter may be null only if stop() was called; modelFilesExist() gating only needed then
        if (spotter == null && !modelFilesExist()) {
            Log.w(TAG, "resume: KWS model files not found")
            return false
        }
        running = true
        captureJob = scope.launch(Dispatchers.IO) {
            runCaptureLoop()  // reuses cached spotter or builds lazily if null
        }
        return true
    }

    private fun runCaptureLoop() {
        // Build the spotter on the IO thread if not yet loaded (first start) or after stop().
        // Reuse the cached instance across pause/resume cycles to avoid re-loading ONNX weights.
        val kws = spotter ?: try {
            buildSpotter().also { spotter = it }
        } catch (err: Throwable) {
            Log.e(TAG, "Failed to build KeywordSpotter: ${err.message}", err)
            running = false
            return
        }

        // Determine minimum AudioRecord buffer size
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned error $minBuf")
            running = false
            return
        }

        val bufferSize = maxOf(minBuf, CHUNK_SAMPLES * 2)  // bytes: 2 bytes per 16-bit sample
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize — state=${audioRecord.state}")
            running = false
            audioRecord.release()
            return
        }

        val stream = kws.createStream()
        val pcmBuf = ShortArray(CHUNK_SAMPLES)
        var chunkCount = 0L
        var lastKeywordMs = 0L
        var totalDecodeSteps = 0L      // cumulative isReady→decode calls, for diagnosing stalls
        var lastDecodeLogChunk = 0L   // last chunk at which we logged decode activity

        try {
            audioRecord.startRecording()
            Log.d(TAG, "AudioRecord started, state=${audioRecord.recordingState}")

            while (running) {
                val read = audioRecord.read(pcmBuf, 0, CHUNK_SAMPLES)
                if (read <= 0) continue

                chunkCount++

                // Convert Short PCM to Float [-1.0, 1.0]
                val floatSamples = FloatArray(read) { pcmBuf[it] / 32768.0f }
                val rms = sqrt(floatSamples.fold(0f) { acc, v -> acc + v * v } / floatSamples.size)

                // Always log when mic picks up speech-level audio (rms > 0.01 ≈ audible voice)
                if (rms > 0.01f) {
                    Log.d(TAG, "chunk=$chunkCount SPEECH rms=${"%.4f".format(rms)}")
                }

                // Periodic audio-flow log (regardless of level, for heartbeat)
                if (chunkCount % LOG_INTERVAL_CHUNKS == 0L) {
                    Log.d(TAG, "chunk=$chunkCount heartbeat rms=${"%.4f".format(rms)} totalDecodeSteps=$totalDecodeSteps")
                }

                stream.acceptWaveform(floatSamples, SAMPLE_RATE)

                // getResult must be checked INSIDE the decode loop — after each individual
                // kws.decode() call — matching the official sherpa-onnx example pattern.
                // Calling it outside the loop means a decode at step N+1 can overwrite the
                // keyword result that was ready at step N before we ever read it.
                var decodeThisChunk = 0
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    totalDecodeSteps++
                    decodeThisChunk++
                    val result = kws.getResult(stream)
                    if (result.keyword.isNotEmpty()) {
                        Log.d(TAG, "KWS raw result: keyword='${result.keyword}' tokens=${result.tokens.take(8)} chunk=$chunkCount")
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sinceLastMs = now - lastKeywordMs
                        if (sinceLastMs >= KEYWORD_COOLDOWN_MS) {
                            lastKeywordMs = now
                            Log.i(TAG, "Keyword detected: '${result.keyword}' chunk=$chunkCount")
                            onKeyword(result.keyword)
                        } else {
                            Log.d(TAG, "Keyword '${result.keyword}' suppressed — cooldown ${sinceLastMs}ms < ${KEYWORD_COOLDOWN_MS}ms chunk=$chunkCount rms=${"%.4f".format(rms)}")
                        }
                        kws.reset(stream)
                    }
                }
                // Log when decoding actually runs (first occurrence, then every 50 chunks)
                if (decodeThisChunk > 0 && (lastDecodeLogChunk == 0L || chunkCount - lastDecodeLogChunk >= 50L)) {
                    Log.d(TAG, "chunk=$chunkCount isReady fired $decodeThisChunk step(s) (total=$totalDecodeSteps)")
                    lastDecodeLogChunk = chunkCount
                }
            }
        } catch (err: Throwable) {
            if (running) Log.e(TAG, "Capture loop error: ${err.message}", err)
        } finally {
            try { audioRecord.stop() } catch (_: Throwable) {}
            audioRecord.release()
            stream.release()
            // Spotter is intentionally NOT released here — it's kept alive for the next resume()
            Log.d(TAG, "AudioRecord released")
        }
    }

    private fun buildSpotter(): KeywordSpotter {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
                debug = false,
            ),
            maxActivePaths = 4,
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 2.0f,
            keywordsThreshold = 0.08f,
            numTrailingBlanks = 1,
        )
        return KeywordSpotter(assetManager = context.assets, config = config)
    }

    private fun modelFilesExist(): Boolean {
        val required = listOf(
            "$MODEL_DIR/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "$MODEL_DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "$MODEL_DIR/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "$MODEL_DIR/tokens.txt",
            "$MODEL_DIR/keywords.txt",
        )
        return try {
            val assetList = context.assets.list(MODEL_DIR) ?: emptyArray()
            // keywords.txt must have non-comment content (actual encoded keywords)
            required.all { path ->
                val name = path.substringAfterLast("/")
                assetList.contains(name)
            } && hasRealKeywords()
        } catch (_: Throwable) {
            false
        }
    }

    /** Returns true if keywords.txt contains at least one non-comment, non-blank line. */
    private fun hasRealKeywords(): Boolean {
        return try {
            context.assets.open("$MODEL_DIR/keywords.txt").bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
                    .any()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
