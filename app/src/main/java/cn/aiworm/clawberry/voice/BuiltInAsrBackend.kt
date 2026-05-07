package clawberry.aiworm.cn.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * [AsrBackend] implementation that delegates to Android's built-in [SpeechRecognizer].
 *
 * All interactions with [SpeechRecognizer] are posted to the main thread as required by the API.
 * The [SpeechRecognizer] instance is created lazily on the first [startListening] call and
 * reused across sessions until [destroy] is called.
 */
class BuiltInAsrBackend(
    private val speechMinSessionMs: Long,
    private val speechCompleteSilenceMs: Long,
    private val speechPossibleSilenceMs: Long,
) : AsrBackend {

    companion object {
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // ── AsrBackend ────────────────────────────────────────────────────────────

    override fun startListening(context: Context, callbacks: AsrCallbacks) {
        mainHandler.post {
            // Lazily create recognizer; set listener each time so callbacks are always fresh.
            val r = recognizer
                ?: SpeechRecognizer.createSpeechRecognizer(context)
                    .also { recognizer = it }
            r.setRecognitionListener(buildListener(callbacks))

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, speechMinSessionMs)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    speechCompleteSilenceMs,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    speechPossibleSilenceMs,
                )
            }
            r.startListening(intent)
        }
    }

    override fun destroy() {
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    // ── RecognitionListener bridge ────────────────────────────────────────────

    private fun buildListener(cb: AsrCallbacks) = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            cb.onReady()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // Normalise to 0f–1f using the same formula used in the original MicCaptureManager.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            cb.onRmsChanged(level)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Do NOT call cb.onEndOfSpeech() here. This fires *before* onResults(), and
            // forwarding it would trigger scheduleRestart(300ms) → startListening on the
            // still-busy recognizer, which can interfere with pending result delivery.
            // onResults() / onError() already call cb.onEndOfSpeech() at the right time.
        }

        override fun onError(error: Int) {
            val isFatal =
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

            val statusText = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported on this device"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable on this device"
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech requests limited; retrying"
                else -> "Speech error ($error)"
            }

            val restartDelayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> 1_200L
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
                else -> 600L
            }

            cb.onError(isFatal, statusText, restartDelayMs)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .firstOrNull()
            if (!text.isNullOrBlank()) cb.onFinal(text.trim())
            // Always signal end-of-speech so the caller schedules the next session.
            cb.onEndOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .firstOrNull()
            if (!text.isNullOrBlank()) cb.onPartial(text.trim())
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
