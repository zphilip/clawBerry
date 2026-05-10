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
  private val asrUrl: () -> String = { "" },
  /**
   * Send [message] to the gateway and return the run ID.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val speakAssistantReply: suspend (String) -> Unit = {},
) {
  companion object {
    private const val tag = "MicCapture"
    private const val speechMinSessionMs = 30_000L
    private const val speechCompleteSilenceMs = 1_500L
    private const val speechPossibleSilenceMs = 900L
    private const val transcriptIdleFlushMs = 1_600L
    private const val maxConversationEntries = 40
    private const val pendingRunTimeoutMs = 45_000L

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

  fun setMicEnabled(enabled: Boolean) {
    if (_micEnabled.value == enabled) return
    _micEnabled.value = enabled
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
      start()
      sendQueuedIfIdle()
    } else {
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
      if (ttsPauseDepth == 0) return@synchronized false
      ttsPauseDepth -= 1
      if (ttsPauseDepth > 0) return@synchronized false
      val resume = resumeMicAfterTts && _micEnabled.value
      resumeMicAfterTts = false
      if (!resume) {
        _statusText.value = if (_isSending.value) "Mic off · sending…" else "Mic off"
      }
      resume
    }
    if (!shouldResume) return
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
    val useBuiltIn = url.isEmpty()
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
          backend = if (useBuiltIn) {
            BuiltInAsrBackend(speechMinSessionMs, speechCompleteSilenceMs, speechPossibleSilenceMs)
          } else {
            FunAsrBackend(url, scope)
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
    val b = backend ?: return
    _statusText.value =
      when {
        _isSending.value -> "${listeningLabel()} · sending queued voice"
        messageQueue.isNotEmpty() -> "${listeningLabel()} · ${messageQueue.size} queued"
        else -> listeningLabel()
      }
    _isListening.value = true
    b.startListening(context, asrCallbacks)
  }

  /** Returns a "Listening · <Backend>" label reflecting the active ASR backend. */
  private fun listeningLabel(): String {
    val suffix = when (backend) {
      is FunAsrBackend -> "FunASR"
      is BuiltInAsrBackend -> "Built-in ASR"
      else -> null
    }
    return if (suffix != null) "Listening · $suffix" else "Listening"
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) return
    if (!_micEnabled.value) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested || !_micEnabled.value) return@post
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
    if (message.isEmpty()) return
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
    _isSending.value = true
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    _statusText.value = if (_micEnabled.value) "Listening · sending queued voice" else "Sending queued voice"

    scope.launch {
      try {
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
    sendQueuedIfIdle()
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
        _inputLevel.value = 0f
        scheduleRestart()
      }
    }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? =
  this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content
