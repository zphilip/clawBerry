package clawberry.aiworm.cn.voice

import android.content.Context

/**
 * Callbacks invoked by an [AsrBackend]. May be called from any thread; implementations
 * of [MicCaptureManager] must be prepared to handle calls off the main thread.
 */
interface AsrCallbacks {
    /** The backend is ready and actively listening for speech. */
    fun onReady()

    /**
     * Microphone input level, normalized to 0f–1f.
     * Emitted continuously while the backend is recording.
     */
    fun onRmsChanged(level: Float)

    /** A partial (in-progress) recognition result. */
    fun onPartial(text: String)

    /**
     * A final recognition result for the current utterance.
     * The backend will call [onEndOfSpeech] shortly after.
     */
    fun onFinal(text: String)

    /**
     * A non-recoverable or recoverable error occurred.
     * @param isFatal  If true the mic should be disabled; if false a restart is appropriate.
     * @param statusText  Human-readable description to show in the UI.
     * @param restartDelayMs  Hint from the backend about how long to wait before restarting.
     */
    fun onError(isFatal: Boolean, statusText: String, restartDelayMs: Long = 600L)

    /**
     * The current recognition session has ended (silence detected / utterance complete).
     * The caller should schedule a new session via [AsrBackend.startListening].
     */
    fun onEndOfSpeech()
}

/**
 * Abstraction over a speech-recognition backend.
 *
 * Lifecycle:
 *  - [startListening] — begin (or restart) a session; lazily initialises underlying resources.
 *  - [startListening] again after [onEndOfSpeech] — starts the next session, reusing resources.
 *  - [destroy]        — fully release underlying resources; backend must not be used after this.
 *
 * All methods are expected to be called from the **main thread**.
 */
interface AsrBackend {
    /**
     * Begin a recognition session.
     * Creates underlying resources on the first call; subsequent calls reuse them.
     */
    fun startListening(context: Context, callbacks: AsrCallbacks)

    /** Fully release underlying resources. Must not be called more than once. */
    fun destroy()
}
