package clawberry.aiworm.cn.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class VoiceConversationRole {
  User,
  Assistant,
}

data class VoiceConversationEntry(
  val id: String,
  val role: VoiceConversationRole,
  val text: String,
  val isStreaming: Boolean = false,
)

class MicCaptureManager(
  private val context: Context,
  private val scope: CoroutineScope,
  /**
   * Returns the FunASR WebSocket URL to use for recognition.
   * When blank/empty the built-in Android [SpeechRecognizer] is used instead.
   */
  private val asrUrl: () -> String = { "" },  /**
   * Returns the user-id to use for speaker-identity-gated transcription.
   * When non-empty, [FunAsrIdentityBackend] is used regardless of [asrUrl].
   */
  private val asrIdentityUserId: () -> String = { ""},  /**
   * Similarity threshold for FunASR-ID speaker verification (0.0–1.0).
   * Lower = more lenient, higher = stricter. Applied per-request so a settings
   * change takes effect immediately on the next utterance.
   */
  private val asrIdentityThreshold: () -> Float = { FunAsrIdentityBackend.DEFAULT_THRESHOLD },  /**
   * Optional [KwsManager] for always-on wake-word detection.
   * When non-null and [kwsEnabled] returns true, KWS listens continuously.
   * Detecting a keyword auto-enables the mic for one ASR session.
   */
  private val kws: KwsManager? = null,
  /** Returns true when keyword spotting wake-word mode is active. */
  private val kwsEnabled: () -> Boolean = { false },  /**
   * Send [message] to the gateway and return the run ID.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val speakAssistantReply: suspend (String) -> Unit = {},
  /**
   * Optional hook invoked (on the coroutine scope) **before** the user's recognized message
   * is dispatched to the gateway, but only during a KWS-triggered session.
   * Use it to play a spoken acknowledgment such as "主人，我马上执行您的命令：{text}".
   * The hook is called with the raw recognized text; the lambda is responsible for any
   * desired formatting. Gateway dispatch waits for the hook to return before proceeding.
   */
  private val speakCommandAck: (suspend (text: String) -> Unit)? = null,
  /**
   * Optional hook invoked when ASR returns an empty result during a KWS session —
   * i.e. the user said something but it couldn't be recognised (identity mismatch, silence, etc.).
   * Play a "please repeat" phrase here.  The pipeline closes normally after this hook returns.
   */
  private val speakRetry: (suspend () -> Unit)? = null,
  /**
   * Optional hook invoked when a KWS-triggered gateway run completes successfully.
   * Play a "command done" phrase here.  The pipeline resumes (next queue item or KWS waiting)
   * after this hook returns.
   */
  private val speakRunComplete: (suspend () -> Unit)? = null,
  /**
   * Optional hook invoked when a KWS session ends with no speech detected at all —
   * i.e. the user said the wake word but then stayed silent until the VAD timeout.
   * Play a "no input heard" phrase here.  The pipeline closes normally after this hook returns.
   */
  private val speakNoInput: (suspend () -> Unit)? = null,
) {
  companion object {
    private const val tag = "MicCapture"
    private const val speechMinSessionMs = 30_000L
    private const val speechCompleteSilenceMs = 1_500L
    private const val speechPossibleSilenceMs = 900L
    private const val transcriptIdleFlushMs = 1_600L
    private const val maxConversationEntries = 40
    private const val pendingRunTimeoutMs = 45_000L
    /** Shorter no-speech timeout for KWS sessions: user should speak immediately after wake word. */
    private const val kwsSpeechTimeoutMs = 8_000L

    /**
     * Only one [MicCaptureManager] may hold the microphone at a time (Android's AudioRecord
     * and SpeechRecognizer are exclusive resources). When a new instance calls [start] it
     * atomically replaces the previous holder here and calls [stopForHandoff] on it, so the
     * previous holder releases the mic before the new one tries to open it.
     */
    private val micOwner = java.util.concurrent.atomic.AtomicReference<MicCaptureManager?>(null)

    /**
     * The last instance that held the mic (persists after the mic is disabled).
     * Used by [toggleActiveMic] to re-enable the mic even when [micOwner] is null
     * (i.e. the mic is off but there's an instance that can be turned back on).
     */
    private val lastKnownMic = java.util.concurrent.atomic.AtomicReference<MicCaptureManager?>(null)

    // ── Global mic state (followed by FloatingOverlayService) ─────────────────
    /** True when any MicCaptureManager currently has its mic enabled. */
    val globalMicEnabled = MutableStateFlow(false)
    /** True while the current owner is actively running an ASR session. */
    val globalMicIsListening = MutableStateFlow(false)

    private val companionScope = kotlinx.coroutines.CoroutineScope(
      kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
    )
    private var ownerEnabledJob: kotlinx.coroutines.Job? = null
    private var ownerListeningJob: kotlinx.coroutines.Job? = null

    /** Start tracking [newOwner]'s state flows into the global state. Pass null to clear. */
    private fun onOwnerChanged(newOwner: MicCaptureManager?) {
      ownerEnabledJob?.cancel()
      ownerListeningJob?.cancel()
      ownerEnabledJob = null
      ownerListeningJob = null
      if (newOwner == null) {
        globalMicEnabled.value = false
        globalMicIsListening.value = false
        return
      }
      lastKnownMic.set(newOwner)  // remember even after mic turns off
      ownerEnabledJob = companionScope.launch {
        newOwner.micEnabled.collect { globalMicEnabled.value = it }
      }
      ownerListeningJob = companionScope.launch {
        newOwner.isListening.collect { globalMicIsListening.value = it }
      }
    }

    /** Toggle the active (or last-active) mic's enabled state. */
    fun toggleActiveMic() {
      // Prefer the current owner; fall back to the last known instance so the user
      // can long-press the float icon to *re-enable* the mic when it is turned off.
      val target = micOwner.get() ?: lastKnownMic.get() ?: return
      val turningOff = target.micEnabled.value
      if (turningOff) userExplicitlyDisabledMic = true
      else userExplicitlyDisabledMic = false  // user is explicitly turning it back on
      target.setMicEnabled(!target.micEnabled.value)
    }

    /**
     * Set by [toggleActiveMic] when the user explicitly turns off the mic from the float
     * overlay. Cleared whenever any mic successfully starts. MainActivity checks this flag
     * in onStop() to avoid re-enabling OpenClaw mic against the user's explicit intent.
     */
    @Volatile var userExplicitlyDisabledMic = false
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }

  private val _micEnabled = MutableStateFlow(false)
  val micEnabled: StateFlow<Boolean> = _micEnabled

  /**
   * Called whenever [setMicEnabled] changes the enabled state (but NOT from [stopForHandoff]).
   * Registered by NodeRuntime to keep [SecurePrefs.talkEnabled] in sync so the in-app
   * voice tab UI reflects the correct state even when the float icon triggers a direct toggle.
   */
  var onMicEnabledChangedHook: ((Boolean) -> Unit)? = null

  private val _micCooldown = MutableStateFlow(false)
  val micCooldown: StateFlow<Boolean> = _micCooldown

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Mic off")
  val statusText: StateFlow<String> = _statusText

  private val _liveTranscript = MutableStateFlow<String?>(null)
  val liveTranscript: StateFlow<String?> = _liveTranscript

  private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
  val queuedMessages: StateFlow<List<String>> = _queuedMessages

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel

  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending

  /**
   * True when the ASR→gateway→TTS pipeline is active and cannot accept a new keyword
   * activation immediately. Includes: ASR listening, waiting for gateway response, mic
   * drain cooldown, and TTS playback.
   */
  val isPipelineActive: Boolean
    get() = kwsSessionActive || _isSending.value || _micCooldown.value || _isListening.value ||
      synchronized(ttsPauseLock) { ttsPauseDepth > 0 }

  /**
   * Queue a keyword activation that arrived while the pipeline was busy.
   * The pipeline will auto-start a new ASR listening session once it goes idle.
   */
  fun setPendingKwsActivation() {
    kwsPendingActivation = true
  }

  private val messageQueue = ArrayDeque<String>()
  private val messageQueueLock = Any()
  private var flushedPartialTranscript: String? = null
  private var pendingAssistantEntryId: String? = null
  private var gatewayConnected = false

  private var backend: AsrBackend? = null
  private var restartJob: Job? = null
  private var drainJob: Job? = null
  private var transcriptFlushJob: Job? = null
  private var pendingRunTimeoutJob: Job? = null
  private var stopRequested = false
  /** Set when KWS fires a keyword — mic auto-turns off again after this session ends. */
  @Volatile private var kwsSessionActive = false
  /** Set when a keyword fires while the pipeline is busy; cleared when pipeline goes idle. */
  @Volatile private var kwsPendingActivation = false
  /** True while a pending/in-flight gateway run was triggered by a KWS session. */
  @Volatile private var pendingRunIsKws = false
  /** True while [FunAsrIdentityBackend] is the active ASR backend (identity-gated mode). */
  @Volatile private var usingIdentityAsr = false
  /**
   * Job running the post-session TTS (speakRetry / speakNoInput / speakRunComplete) launched
   * from within a KWS or identity-ASR session.  [scheduleRestart] joins this before re-opening
   * the KWS [AudioRecord] so the mic doesn’t capture TTS audio and corrupt the stream state.
   */
  private var kwsPostSessionTtsJob: Job? = null
  /** True once [AsrCallbacks.onFinal] fires for the current session; reset at session start. */
  @Volatile private var kwsOnFinalReceived = false
  private val ttsPauseLock = Any()
  private var ttsPauseDepth = 0
  private var resumeMicAfterTts = false
  @Volatile private var pendingRunId: String? = null
  @Volatile private var pendingAltRunId: String? = null

  /**
   * Events that arrive while we hold only the idempotency key (pendingRunId = earlyKey) but the
   * server has already started streaming with its own runId.  Drained once pendingRunId is updated
   * to the real server runId by [sendQueuedIfIdle].
   */
  private val earlyEventBuffer = ArrayDeque<String>()   // payloadJson strings
  private val earlyEventLock   = Any()

  private fun enqueueMessage(message: String) {
    synchronized(messageQueueLock) { messageQueue.addLast(message) }
  }

  private fun hasQueuedMessages(): Boolean = synchronized(messageQueueLock) { messageQueue.isNotEmpty() }

  private fun firstQueuedMessage(): String? = synchronized(messageQueueLock) { messageQueue.firstOrNull() }

  private fun removeFirstQueuedMessage(): String? =
    synchronized(messageQueueLock) { if (messageQueue.isEmpty()) null else messageQueue.removeFirst() }

  private fun queuedMessageCount(): Int = synchronized(messageQueueLock) { messageQueue.size }

  /**
   * Swap the ASR backend immediately without disabling the mic.
   * Call this when the ASR provider setting changes (e.g. built-in ↔ FunASR)
   * while the mic is already running, so the new [asrUrl] is picked up right away.
   * No-op if the mic is currently disabled.
   */
  fun switchAsr() {
    if (!_micEnabled.value) return
    stop()
    start()
  }

  /**
   * Start always-on keyword spotting. Call this after KWS is enabled in settings.
   * No-op if KWS is not configured or [kwsEnabled] returns false.
   */
  fun startKws() {
    if (kws == null || !kwsEnabled()) return
    kws.start()
    Log.d(tag, "KWS started")
  }

  /**
   * Stop keyword spotting. Call when KWS is disabled in settings or the instance is destroyed.
   */
  fun stopKws() {
    kws?.stop()
    Log.d(tag, "KWS stopped")
  }

  /**
   * Called when [KwsManager] detects a keyword.
   * Marks this as a KWS-triggered session so the mic is automatically turned off
   * again when the session ends (returning to keyword-waiting state).
   */
  fun notifyKeyword(keyword: String) {
    Log.i(tag, "notifyKeyword: '$keyword' micEnabled=${_micEnabled.value} stopRequested=$stopRequested")
    kwsSessionActive = true
    val kwsJob = kws?.pause()  // cancel KWS capture coroutine; returns job to await
    if (!_micEnabled.value) {
      scope.launch(Dispatchers.IO) {
        kwsJob?.join()  // wait until KWS AudioRecord is fully released in the finally block
        mainHandler.post { setMicEnabled(true) }
      }
    } else {
      // Always restart on keyword — stopRequested may be true from previous session end, ignore it
      stop()
      scope.launch(Dispatchers.IO) {
        kwsJob?.join()  // wait until KWS AudioRecord is fully released in the finally block
        mainHandler.post { start() }
      }
    }
  }

  fun setMicEnabled(enabled: Boolean) {
    if (_micEnabled.value == enabled) return
    _micEnabled.value = enabled
    onMicEnabledChangedHook?.invoke(enabled)
    if (enabled) {
      val pausedForTts =
        synchronized(ttsPauseLock) {
          if (ttsPauseDepth > 0) {
            resumeMicAfterTts = true
            true
          } else {
            false
          }
        }
      if (pausedForTts) {
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        return
      }
      startKws()  // ensure KWS is running whenever mic turns on (idempotent, no-op if disabled)
      if (kwsEnabled()) {
        // KWS mode: don't launch ASR — stay quiet and wait for wake word.
        // Update lastKnownMic and globalMicEnabled so the float overlay badge and
        // toggleActiveMic() operate correctly in the "waiting for keyword" state.
        lastKnownMic.set(this)
        globalMicEnabled.value = true
        _statusText.value = "Listening for wake word…"
        return
      }
      start()
      sendQueuedIfIdle()
    } else {
      // Stop KWS immediately so it doesn't fire a keyword activation while the mic is
      // explicitly off. Also clear the global state right away so the float badge updates.
      stopKws()
      globalMicEnabled.value = false
      // Give the recognizer time to finish processing buffered audio.
      // Cancel any prior drain to prevent duplicate sends on rapid toggle.
      drainJob?.cancel()
      _micCooldown.value = true
      drainJob = scope.launch {
        delay(2000L)
        stop()
        // Capture any partial transcript that didn't get a final result from the recognizer
        val partial = _liveTranscript.value?.trim().orEmpty()
        if (partial.isNotEmpty()) {
          queueRecognizedMessage(partial)
        }
        drainJob = null
        _micCooldown.value = false
        sendQueuedIfIdle()
      }
    }
  }

  /** Destroy the ASR backend before TTS playback to prevent audio session conflicts. */
  suspend fun pauseForTts() {
    val shouldPause = synchronized(ttsPauseLock) {
      ttsPauseDepth += 1
      Log.d(tag, "pauseForTts: depth=$ttsPauseDepth micEnabled=${_micEnabled.value}")
      if (ttsPauseDepth > 1) return@synchronized false
      resumeMicAfterTts = _micEnabled.value
      val active = resumeMicAfterTts || backend != null || _isListening.value
      if (!active) return@synchronized false
      stopRequested = true
      restartJob?.cancel()
      restartJob = null
      transcriptFlushJob?.cancel()
      transcriptFlushJob = null
      drainJob?.cancel()
      drainJob = null
      _isListening.value = false
      _inputLevel.value = 0f
      _liveTranscript.value = null
      _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
      true
    }
    if (!shouldPause) return
    withContext(Dispatchers.Main) {
      backend?.destroy()
      backend = null
    }
  }

  /** Recreate the ASR backend after TTS playback if it was active before. */
  suspend fun resumeAfterTts() {
    val shouldResume = synchronized(ttsPauseLock) {
      if (ttsPauseDepth == 0) {
        Log.w(tag, "resumeAfterTts: depth already 0 — unbalanced call?")
        return@synchronized false
      }
      ttsPauseDepth -= 1
      Log.d(tag, "resumeAfterTts: depth=$ttsPauseDepth resumeMicAfterTts=$resumeMicAfterTts micEnabled=${_micEnabled.value}")
      if (ttsPauseDepth > 0) return@synchronized false
      val resume = resumeMicAfterTts && _micEnabled.value
      resumeMicAfterTts = false
      if (!resume) {
        _statusText.value = if (_isSending.value) "Mic off · sending…" else "Mic off"
      }
      resume
    }
    if (!shouldResume) {
      // In KWS mode the mic was off, so normal resume is skipped. Activate any queued
      // keyword session now that TTS has finished playing (NodeRuntime path).
      if (kwsPendingActivation && kwsEnabled()) {
        kwsPendingActivation = false
        activatePendingKwsSession()
      }
      return
    }
    stopRequested = false
    start()
    sendQueuedIfIdle()
  }

  fun onGatewayConnectionChanged(connected: Boolean) {
    gatewayConnected = connected
    if (connected) {
      sendQueuedIfIdle()
      return
    }
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    pendingRunId = null
    pendingAltRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    if (hasQueuedMessages()) {
      _statusText.value = queuedWaitingStatus()
    }
  }

  fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (event != "chat") return
    if (payloadJson.isNullOrBlank()) return
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val runId = pendingRunId ?: run { Log.d("MicCapture", "no pendingRunId — drop"); return }
    val eventRunId = payload["runId"].asStringOrNull() ?: return
    if (eventRunId != runId && eventRunId != pendingAltRunId) {
      // The server may be streaming events with its real runId while we still hold only the
      // idempotency key (the window between onRunIdKnown and chat.send returning).  Buffer
      // these events so they can be replayed once pendingRunId is updated to serverRunId.
      if (_isSending.value) {
        synchronized(earlyEventLock) { earlyEventBuffer.addLast(payloadJson) }
        Log.d("MicCapture", "runId mismatch — buffered (event=$eventRunId, pending=$runId)")
      } else {
        Log.d("MicCapture", "runId mismatch: event=$eventRunId pending=$runId alt=$pendingAltRunId")
      }
      return
    }

    processGatewayPayload(payload)
  }

  /**
   * Replay any events buffered during the idempotency-key window for [serverRunId].  Must be
   * called on the same thread/coroutine that updates [pendingRunId] to [serverRunId].
   */
  private fun drainEarlyEventBuffer(serverRunId: String, altRunId: String?) {
    val toProcess = synchronized(earlyEventLock) {
      val matching = earlyEventBuffer
        .mapNotNull { pJson ->
          try { json.parseToJsonElement(pJson).asObjectOrNull()?.let { pJson to it } } catch (_: Throwable) { null }
        }
        .filter { (_, p) ->
          val id = p["runId"].asStringOrNull()
          id == serverRunId || (altRunId != null && id == altRunId)
        }
        .map { it.second }   // keep JsonObject, discard raw string
      earlyEventBuffer.clear()  // discard non-matching entries
      matching
    }
    if (toProcess.isNotEmpty()) {
      Log.d("MicCapture", "draining ${toProcess.size} buffered event(s) for runId=$serverRunId")
      toProcess.forEach { processGatewayPayload(it) }
    }
  }

  private fun processGatewayPayload(payload: kotlinx.serialization.json.JsonObject) {
    when (payload["state"].asStringOrNull()) {
      "delta" -> {
        val deltaText = parseAssistantText(payload)
        if (!deltaText.isNullOrBlank()) {
          upsertPendingAssistant(text = deltaText.trim(), isStreaming = true)
        }
      }
      "final" -> {
        val finalText = parseAssistantText(payload)?.trim().orEmpty()
        val textToSpeak: String
        if (finalText.isNotEmpty()) {
          // Final event carries the complete text — use it directly.
          upsertPendingAssistant(text = finalText, isStreaming = false)
          textToSpeak = finalText
        } else {
          // Final event is a completion signal with no body — the server streamed
          // the full reply via delta events. Finalize the streaming entry and
          // use its accumulated text for TTS.
          val entryId = pendingAssistantEntryId
          val accumulated = entryId
            ?.let { id -> _conversation.value.find { it.id == id }?.text.orEmpty() }
            .orEmpty()
          if (entryId != null) {
            updateConversationEntry(entryId, text = null, isStreaming = false)
          }
          textToSpeak = accumulated
        }
        if (textToSpeak.isNotBlank()) {
          playAssistantReplyAsync(textToSpeak)
        }
        completePendingTurn()
      }
      "error" -> {
        val errorMessage = payload["errorMessage"].asStringOrNull()?.trim().orEmpty().ifEmpty { "Voice request failed" }
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        completePendingTurn()
      }
      "aborted" -> {
        upsertPendingAssistant(text = "Response aborted", isStreaming = false)
        completePendingTurn()
      }
    }
  }

  private fun start() {
    stopRequested = false
    val url = asrUrl().trim()
    val identityUserIdForStart = asrIdentityUserId().trim()
    val useBuiltIn = url.isEmpty() && identityUserIdForStart.isEmpty()
    if (useBuiltIn && !BuiltInAsrBackend.isAvailable(context)) {
      _statusText.value = "Speech recognizer unavailable"
      _micEnabled.value = false
      return
    }
    if (!hasMicPermission()) {
      _statusText.value = "Microphone permission required"
      _micEnabled.value = false
      return
    }

    // Claim exclusive mic ownership. If another MicCaptureManager is active, stop it
    // immediately (without the 2-second drain) so it releases AudioRecord/SpeechRecognizer
    // before we try to open them. Both stopForHandoff and the backend-create below are
    // posted to mainHandler in order, so destroy always precedes create.
    val prev = micOwner.getAndSet(this)
    if (prev != null && prev !== this) {
      Log.d(tag, "mic handoff: stopping previous holder")
      prev.stopForHandoff()
    }
    onOwnerChanged(this)  // begin tracking this instance's flows into global state
    userExplicitlyDisabledMic = false  // user started mic — clear any prior explicit-off flag

    mainHandler.post {
      try {
        if (backend == null) {
          val identityUserId = asrIdentityUserId().trim()
          usingIdentityAsr = identityUserId.isNotEmpty()
          backend = when {
            identityUserId.isNotEmpty() -> FunAsrIdentityBackend(
              identityUserId, scope, asrIdentityThreshold,
              // KWS sessions: shorter timeout so the user doesn't wait 20s after
              // saying the wake word and then going silent.
              speechTimeoutMs = if (kwsSessionActive) kwsSpeechTimeoutMs
                                else FunAsrIdentityBackend.SPEECH_TIMEOUT_MS,
            )
            !useBuiltIn -> FunAsrBackend(url, scope)
            else -> BuiltInAsrBackend(speechMinSessionMs, speechCompleteSilenceMs, speechPossibleSilenceMs)
          }
        }
        startListeningSession()
      } catch (err: Throwable) {
        _statusText.value = "Start failed: ${err.message ?: err::class.simpleName}"
        _micEnabled.value = false
      }
    }
  }

  private fun stop() {
    val wasOwner = micOwner.compareAndSet(this, null)
    if (wasOwner) onOwnerChanged(null)
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _isListening.value = false
    _statusText.value = if (_isSending.value) "Mic off · sending…" else "Mic off"
    _inputLevel.value = 0f
    mainHandler.post {
      backend?.destroy()
      backend = null
    }
  }

  /**
   * Immediate stop (no drain) called when another [MicCaptureManager] takes ownership of the
   * mic. Posts backend destroy to mainHandler so it is ordered before the new owner's create.
   */
  private fun stopForHandoff() {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _isListening.value = false
    _inputLevel.value = 0f
    _micEnabled.value = false
    mainHandler.post {
      backend?.destroy()
      backend = null
    }
  }

  private fun startListeningSession() {
    val b = backend ?: run {
      Log.w(tag, "startListeningSession: backend is null — skipping (stopRequested=$stopRequested micEnabled=${_micEnabled.value})")
      return
    }
    Log.d(tag, "startListeningSession: backend=${b::class.simpleName}")
    _statusText.value =
      when {
        _isSending.value -> "${listeningLabel()} · sending queued voice"
        messageQueue.isNotEmpty() -> "${listeningLabel()} · ${messageQueue.size} queued"
        else -> listeningLabel()
      }
    kwsOnFinalReceived = false  // reset per session; set true by onFinal before onEndOfSpeech
    _isListening.value = true
    b.startListening(context, asrCallbacks)
  }

  /** Returns a "Listening · <Backend>" label reflecting the active ASR backend. */
  private fun listeningLabel(): String {
    val suffix = when (backend) {
      is FunAsrIdentityBackend -> "FunASR-ID"
      is FunAsrBackend -> "FunASR"
      is BuiltInAsrBackend -> "Built-in ASR"
      else -> null
    }
    return if (suffix != null) "Listening · $suffix" else "Listening"
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) {
      Log.d(tag, "scheduleRestart skipped: stopRequested=true")
      return
    }
    if (!_micEnabled.value) {
      Log.d(tag, "scheduleRestart skipped: micEnabled=false")
      return
    }
    // KWS mode: don't auto-restart — stop the backend and wait for next wake word.
    // This applies whether the mic was manually on or keyword-triggered.
    if (kwsEnabled()) {
      kwsSessionActive = false
      Log.d(tag, "scheduleRestart: KWS mode — session ended, waiting for wake word")
      stop()  // clears micOwner — globalMicEnabled drops to false inside onOwnerChanged(null)
      // Re-assert globalMicEnabled and lastKnownMic since we’re back in KWS waiting state.
      // Without this, the float badge would incorrectly show mic as "off" until next keyword.
      lastKnownMic.set(this)
      globalMicEnabled.value = true
      _statusText.value = "Listening for wake word…"
      // Delay before re-opening KWS AudioRecord: lets Android audio routing fully settle
      // after the ASR AudioRecord closes. Without this, the new AudioRecord may open while
      // the previous audio session is still being torn down, resulting in attenuated capture.
      // Also join any in-flight post-session TTS (retry prompt) so the KWS mic doesn't
      // capture TTS audio and corrupt the keyword-spotter stream state.
      val kwsRef = kws
      val ttsJob = kwsPostSessionTtsJob.also { kwsPostSessionTtsJob = null }
      scope.launch(Dispatchers.IO) {
        ttsJob?.join()  // wait for retry/success audio to finish before opening the mic
        delay(400L)
        kwsRef?.resume()
      }
      return
    }
    Log.d(tag, "scheduleRestart in ${delayMs}ms")
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested) {
            Log.d(tag, "scheduleRestart post: skipped — stopRequested=true")
            return@post
          }
          if (!_micEnabled.value) {
            Log.d(tag, "scheduleRestart post: skipped — micEnabled=false")
            return@post
          }
          try {
            startListeningSession()
          } catch (_: Throwable) {
            // retry through onError
          }
        }
      }
  }

  private fun queueRecognizedMessage(text: String) {
    val message = text.trim()
    _liveTranscript.value = null
    if (message.isEmpty()) {
      // Play retry prompt when:
      //  • KWS session: empty = recognition failed after wake word
      //  • Identity ASR: empty = server actively rejected the speaker (identity mismatch)
      if (kwsSessionActive || usingIdentityAsr) {
        kwsPostSessionTtsJob = scope.launch {
          try { speakRetry?.invoke() }
          catch (e: Throwable) { Log.w(tag, "speakRetry failed: ${e.message}") }
        }
      }
      return
    }
    appendConversation(
      role = VoiceConversationRole.User,
      text = message,
    )
    enqueueMessage(message)
    publishQueue()
  }

  private fun scheduleTranscriptFlush(expectedText: String) {
    transcriptFlushJob?.cancel()
    transcriptFlushJob =
      scope.launch {
        delay(transcriptIdleFlushMs)
        if (!_micEnabled.value || _isSending.value) return@launch
        val current = _liveTranscript.value?.trim().orEmpty()
        if (current.isEmpty() || current != expectedText) return@launch
        flushedPartialTranscript = current
        queueRecognizedMessage(current)
        sendQueuedIfIdle()
      }
  }

  private fun publishQueue() {
    _queuedMessages.value = synchronized(messageQueueLock) { messageQueue.toList() }
  }

  private fun sendQueuedIfIdle() {
    if (_isSending.value) return
    if (!hasQueuedMessages()) {
      if (_micEnabled.value) {
        _statusText.value = listeningLabel()
      } else {
        _statusText.value = "Mic off"
      }
      return
    }
    if (!gatewayConnected) {
      _statusText.value = queuedWaitingStatus()
      return
    }

    val next = firstQueuedMessage() ?: return
    val isKwsSession = kwsSessionActive  // capture before scope.launch — onEndOfSpeech may clear it
    _isSending.value = true
    if (isKwsSession) pendingRunIsKws = true
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    _statusText.value = if (_micEnabled.value) "Listening · sending queued voice" else "Sending queued voice"

    scope.launch {
      try {
        // During a KWS-triggered session, play a spoken acknowledgment before dispatching
        // to the gateway.  The hook suspends until TTS is done so the user hears the ack
        // before the AI reply starts.
        if (isKwsSession) {
          speakCommandAck?.invoke(next)
        }
        val runId = sendToGateway(next) { earlyRunId ->
          // Called with the idempotency key before chat.send fires so that
          // pendingRunId is populated before any chat events can arrive.
          pendingRunId = earlyRunId
          pendingAltRunId = earlyRunId
        }
        // Update to the real runId if the gateway returned a different one.
        // Also drain any events that arrived before the response while we only held the
        // idempotency key (the runId mismatch window).
        if (runId != null && runId != pendingRunId) {
          pendingRunId = runId
          drainEarlyEventBuffer(runId, pendingAltRunId)
        } else if (runId != null) {
          // idempotency key IS the server runId — still drain in case something was buffered
          drainEarlyEventBuffer(runId, null)
        }
        if (runId == null) {
          pendingRunTimeoutJob?.cancel()
          pendingRunTimeoutJob = null
          removeFirstQueuedMessage()
          publishQueue()
          _isSending.value = false
          pendingAssistantEntryId = null
          sendQueuedIfIdle()
        } else {
          armPendingRunTimeout(runId)
        }
      } catch (err: Throwable) {
        pendingRunTimeoutJob?.cancel()
        pendingRunTimeoutJob = null
        _isSending.value = false
        pendingRunId = null
        pendingAltRunId = null
        pendingAssistantEntryId = null
        _statusText.value =
          if (!gatewayConnected) {
            queuedWaitingStatus()
          } else {
            "Send failed: ${err.message ?: err::class.simpleName}"
          }
      }
    }
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob =
      scope.launch {
        delay(pendingRunTimeoutMs)
        if (pendingRunId != runId && pendingAltRunId != runId) return@launch
        pendingRunId = null
        pendingAltRunId = null
        pendingAssistantEntryId = null
        _isSending.value = false
        _statusText.value =
          if (gatewayConnected) {
            "Voice reply timed out; retrying queued turn"
          } else {
            queuedWaitingStatus()
          }
        sendQueuedIfIdle()
      }
  }

  // ---------------------------------------------------------------------------
  // External assistant injection (for non-OpenClaw gateways like Zero/Pico)
  // ---------------------------------------------------------------------------

  /**
   * Call when a streaming chunk arrives from an external gateway (Zero/Pico) that
   * doesn't use the OpenClaw runId event format.  Appends or updates the pending
   * assistant conversation entry and shows the "sending" indicator.
   */
  fun onExternalAssistantDelta(text: String) {
    if (text.isBlank()) return
    _isSending.value = true
    upsertPendingAssistant(text = text, isStreaming = true)
  }

  /**
   * Call when the external gateway signals the assistant turn is complete.
   * [fullText] is the final accumulated reply — pass empty string to finalize
   * whatever partial text was already shown.
   */
  fun onExternalAssistantComplete(fullText: String) {
    val entryId = pendingAssistantEntryId
    if (fullText.isNotBlank()) {
      upsertPendingAssistant(text = fullText, isStreaming = false)
    } else if (entryId != null) {
      updateConversationEntry(entryId, text = null, isStreaming = false)
    }
    pendingAssistantEntryId = null
    _isSending.value = false
    if (_micEnabled.value) _statusText.value = listeningLabel()
    val wasKws = pendingRunIsKws
    pendingRunIsKws = false
    if (wasKws) {
      scope.launch {
        try { speakRunComplete?.invoke() }
        catch (e: Throwable) { Log.w(tag, "speakRunComplete (external) failed: ${e.message}") }
      }
    }
  }

  // ---------------------------------------------------------------------------

  private fun completePendingTurn() {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    synchronized(earlyEventLock) { earlyEventBuffer.clear() }
    if (removeFirstQueuedMessage() != null) {
      publishQueue()
    }
    pendingRunId = null
    pendingAltRunId = null
    pendingAssistantEntryId = null
    _isSending.value = false
    val wasKws = pendingRunIsKws
    pendingRunIsKws = false
    if (wasKws) {
      scope.launch {
        try { speakRunComplete?.invoke() }
        catch (e: Throwable) { Log.w(tag, "speakRunComplete failed: ${e.message}") }
      }
    }
    // Activate a queued keyword session when no TTS is currently playing (Zero/PicoClaw path).
    // When TTS IS active (NodeRuntime), resumeAfterTts() handles it once TTS finishes.
    if (kwsPendingActivation && kwsEnabled() && synchronized(ttsPauseLock) { ttsPauseDepth == 0 }) {
      kwsPendingActivation = false
      activatePendingKwsSession()
    } else {
      sendQueuedIfIdle()
    }
  }

  /**
   * Start an ASR session for a keyword activation that was queued while the pipeline was busy.
   * Bypasses the normal KWS "wait for wake word" gate in [setMicEnabled] so ASR starts immediately.
   */
  private fun activatePendingKwsSession() {
    Log.i(tag, "activatePendingKwsSession: starting queued KWS session")
    val kwsJob = kws?.pause()  // cancel KWS capture coroutine; returns job to await
    kwsSessionActive = true
    _micEnabled.value = true
    scope.launch(Dispatchers.IO) {
      kwsJob?.join()  // wait until KWS AudioRecord is fully released before opening a new one
      mainHandler.post { start() }
    }
  }

  private fun queuedWaitingStatus(): String {
    return "${queuedMessageCount()} queued · waiting for gateway"
  }

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(id: String, text: String?, isStreaming: Boolean) {
    val current = _conversation.value
    if (current.isEmpty()) return

    val targetIndex =
      when {
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return

    val entry = current[targetIndex]
    val updatedText = text ?: entry.text
    if (updatedText == entry.text && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun upsertPendingAssistant(text: String, isStreaming: Boolean) {
    val currentId = pendingAssistantEntryId
    if (currentId == null) {
      pendingAssistantEntryId =
        appendConversation(
          role = VoiceConversationRole.Assistant,
          text = text,
          isStreaming = isStreaming,
        )
      return
    }
    updateConversationEntry(id = currentId, text = text, isStreaming = isStreaming)
  }

  private fun playAssistantReplyAsync(text: String) {
    val spoken = text.trim()
    if (spoken.isEmpty()) return
    scope.launch {
      try {
        speakAssistantReply(spoken)
      } catch (err: Throwable) {
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun disableMic(status: String) {
    val wasOwner = micOwner.compareAndSet(this, null)
    if (wasOwner) onOwnerChanged(null)
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _micEnabled.value = false
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = status
    mainHandler.post {
      backend?.destroy()
      backend = null
    }
  }

  private fun hasMicPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null

    val parts =
      content.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        if (obj["type"].asStringOrNull() != "text") return@mapNotNull null
        obj["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  /**
   * Unified [AsrCallbacks] instance shared by both [BuiltInAsrBackend] and [FunAsrBackend].
   * Encapsulates all the queue / transcript / restart logic that was previously spread across
   * the [android.speech.RecognitionListener] methods.
   */
  private val asrCallbacks: AsrCallbacks =
    object : AsrCallbacks {
      override fun onReady() {
        _isListening.value = true
      }

      override fun onRmsChanged(level: Float) {
        _inputLevel.value = level
      }

      override fun onPartial(text: String) {
        _liveTranscript.value = text
        scheduleTranscriptFlush(text)
      }

      override fun onFinal(text: String) {
        kwsOnFinalReceived = true  // mark that the backend did produce a result (even if empty)
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        if (text != flushedPartialTranscript) {
          queueRecognizedMessage(text)
          sendQueuedIfIdle()
        } else {
          flushedPartialTranscript = null
          _liveTranscript.value = null
        }
      }

      override fun onError(isFatal: Boolean, statusText: String, restartDelayMs: Long) {
        if (stopRequested) return
        _isListening.value = false
        _inputLevel.value = 0f
        _statusText.value = statusText
        if (isFatal) {
          disableMic(statusText)
          return
        }
        scheduleRestart(delayMs = restartDelayMs)
      }

      override fun onEndOfSpeech() {
        Log.d(tag, "onEndOfSpeech: stopRequested=$stopRequested micEnabled=${_micEnabled.value} backend=${backend?.let { it::class.simpleName }}")
        _inputLevel.value = 0f
        // KWS session ended with no speech at all (VAD timeout, user said nothing after
        // the wake word) — play "no input heard" prompt before resuming KWS listening.
        if (kwsSessionActive && !kwsOnFinalReceived && speakNoInput != null) {
          kwsPostSessionTtsJob = scope.launch {
            try { speakNoInput.invoke() }
            catch (e: Throwable) { Log.w(tag, "speakNoInput failed: ${e.message}") }
          }
        }
        scheduleRestart()
      }
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? =
  this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content
