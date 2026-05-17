package clawberry.aiworm.cn.zeroclaw

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import clawberry.aiworm.cn.voice.KwsManager
import clawberry.aiworm.cn.voice.KwsTtsPlayer
import clawberry.aiworm.cn.voice.MicCaptureManager
import clawberry.aiworm.cn.voice.VoiceConversationEntry

// ---------------------------------------------------------------------------
// State machine
// ---------------------------------------------------------------------------
enum class ZcState {
  Idle,
  HealthChecking,
  NeedsPairing,
  Pairing,
  Connecting,
  Connected,
  Reconnecting,
  Error,
}

// ---------------------------------------------------------------------------
// Attachment + chat message models
// ---------------------------------------------------------------------------
data class ZcImageAttachment(
  val mimeType: String,
  val fileName: String,
  val base64: String,
)

data class ZcChatMessage(
  val id: String = UUID.randomUUID().toString(),
  /** "user" | "assistant" | "tool_call" | "tool_result" | "system_error" */
  val role: String,
  val content: String,
  val isStreaming: Boolean = false,
  val attachments: List<ZcImageAttachment> = emptyList(),
)

// ---------------------------------------------------------------------------
// Session picker state
// ---------------------------------------------------------------------------
sealed class ZcSessionPickerState {
  data object Hidden : ZcSessionPickerState()
  data class Choosing(val sessions: List<ZcSessionInfo>) : ZcSessionPickerState()
  data object Restoring : ZcSessionPickerState()
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------
class ZeroClawViewModel(app: Application) : AndroidViewModel(app) {

  private val prefs = app.getSharedPreferences("zeroclaw.direct", Context.MODE_PRIVATE)
  private val asrPrefs = app.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)  // never timeout open WS
    .build()
  private val session = ZeroClawSession(client)

  // Session ID is kept stable across reconnects so the server can restore
  // conversation context.  Only reset when the user explicitly disconnects.
  private var sessionId: String = UUID.randomUUID().toString()

  // --- Persisted connection settings ---
  val host = MutableStateFlow(prefs.getString("host", "10.0.2.2") ?: "10.0.2.2")
  val port = MutableStateFlow(prefs.getInt("port", 42617))
  val pairCode = MutableStateFlow("")
  val tokenInput = MutableStateFlow("")

  // --- Proxy settings ---
  val useProxy = MutableStateFlow(prefs.getBoolean("use_proxy", false))
  val proxyPort = MutableStateFlow(prefs.getInt("proxy_port", 18780))

  private val _asrUrl = MutableStateFlow(
    asrPrefs.getString("asr.url", "wss://asr.aiworm.cn:443") ?: "wss://asr.aiworm.cn:443",
  )
  val asrUrl: StateFlow<String> = _asrUrl.asStateFlow()

  private val _useCustomAsr = MutableStateFlow(asrPrefs.getBoolean("asr.useCustom", false))
  val useCustomAsr: StateFlow<Boolean> = _useCustomAsr.asStateFlow()
  private val _useIdentityAsr = MutableStateFlow(asrPrefs.getBoolean("asr.useIdentity", false))
  val useIdentityAsr: StateFlow<Boolean> = _useIdentityAsr.asStateFlow()

  private val _kwsEnabled = MutableStateFlow(asrPrefs.getBoolean("asr.kwsEnabled", false))
  val kwsEnabled: StateFlow<Boolean> = _kwsEnabled.asStateFlow()

  private val _kwsGreeting = MutableStateFlow(
    asrPrefs.getString("asr.kwsGreeting", "你好主人") ?: "你好主人",
  )
  val kwsGreeting: StateFlow<String> = _kwsGreeting.asStateFlow()

  private val _kwsTitle = MutableStateFlow(
    asrPrefs.getString("asr.kwsTitle", "主人") ?: "主人",
  )
  val kwsTitle: StateFlow<String> = _kwsTitle.asStateFlow()

  private val _kwsRetryPhrase = MutableStateFlow(
    asrPrefs.getString("asr.kwsRetryPhrase", "请再说一遍你的指令") ?: "请再说一遍你的指令",
  )
  val kwsRetryPhrase: StateFlow<String> = _kwsRetryPhrase.asStateFlow()

  private val _kwsSuccessPhrase = MutableStateFlow(
    asrPrefs.getString("asr.kwsSuccessPhrase", "你的指令运行完毕") ?: "你的指令运行完毕",
  )
  val kwsSuccessPhrase: StateFlow<String> = _kwsSuccessPhrase.asStateFlow()

  private val _kwsAckPhrase = MutableStateFlow(
    asrPrefs.getString("asr.kwsAckPhrase", "我马上执行您的命令") ?: "我马上执行您的命令",
  )
  val kwsAckPhrase: StateFlow<String> = _kwsAckPhrase.asStateFlow()

  init {
    // Sync globalIsRegistered from persisted prefs so voice chip shows correct state
    // even before the user opens the Settings tab (which creates SpeakerRegistrationManager).
    clawberry.aiworm.cn.voice.SpeakerRegistrationManager.loadRegistrationState(app.applicationContext)
  }

  private val _token = MutableStateFlow<String?>(prefs.getString("token", null))
  val token: StateFlow<String?> = _token.asStateFlow()

  // --- UI state ---
  private val _state = MutableStateFlow(ZcState.Idle)
  val state: StateFlow<ZcState> = _state.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private val _messages = MutableStateFlow<List<ZcChatMessage>>(emptyList())
  val messages: StateFlow<List<ZcChatMessage>> = _messages.asStateFlow()

  private val _sessionPicker = MutableStateFlow<ZcSessionPickerState>(ZcSessionPickerState.Hidden)
  val sessionPicker: StateFlow<ZcSessionPickerState> = _sessionPicker.asStateFlow()

  // --- Voice (always-listening mic) ---
  private val kwsTtsPlayer = KwsTtsPlayer(app.applicationContext, viewModelScope).also {
    it.updateGreeting(_kwsGreeting.value)
    it.updateRetryPhrase("${_kwsTitle.value}，${_kwsRetryPhrase.value}")
    it.updateSuccessPhrase("${_kwsTitle.value}，${_kwsSuccessPhrase.value}")
    it.init()
  }
  private val mic: MicCaptureManager = MicCaptureManager(
    context = app.applicationContext,
    scope = viewModelScope,
    asrUrl = {
      if (_useCustomAsr.value && _asrUrl.value.isNotBlank()) _asrUrl.value else ""
    },
    asrIdentityUserId = {
      if (_useIdentityAsr.value) asrPrefs.getString("node.instanceId", "") ?: "" else ""
    },
    asrIdentityThreshold = { asrPrefs.getFloat("asr.identityThreshold", 0.45f) },
    kws = KwsManager(
      context = app.applicationContext,
      scope = viewModelScope,
      onKeyword = { keyword ->
        android.util.Log.i("ZeroClawVM", "KWS keyword: '$keyword'")
        if (mic.isPipelineActive) {
          kwsTtsPlayer.playText("${_kwsTitle.value}，我正在处理您的上一条指令，完成后即为您服务")
          mic.setPendingKwsActivation()
        } else {
          // Start ASR only after the greeting audio finishes so the mic cannot
          // pick up the greeting and echo it back as user speech.
          kwsTtsPlayer.playGreeting {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
              mic.notifyKeyword(keyword)
            }
          }
        }
      },
    ),
    kwsEnabled = { _kwsEnabled.value },
    sendToGateway = { message, onRunIdKnown ->
      onRunIdKnown(UUID.randomUUID().toString())
      sendMessage(message)
      null  // no runId tracking — responses come via onExternalAssistant*
    },
    speakAssistantReply = {},   // no TTS for Zero/Pico voice tab
    speakCommandAck = { text -> kwsTtsPlayer.playTextSuspend("${_kwsTitle.value}，${_kwsAckPhrase.value}：$text") },
    speakRetry = { kwsTtsPlayer.playRetrySuspend() },
    speakRunComplete = { kwsTtsPlayer.playSuccessSuspend() },
    speakNoInput = { kwsTtsPlayer.playTextSuspend("${_kwsTitle.value}，没有听到你的指令") },
  )

  val micEnabled: StateFlow<Boolean> = mic.micEnabled
  val micCooldown: StateFlow<Boolean> = mic.micCooldown
  val micIsListening: StateFlow<Boolean> = mic.isListening
  val micStatusText: StateFlow<String> = mic.statusText
  val micLiveTranscript: StateFlow<String?> = mic.liveTranscript
  val micConversation: StateFlow<List<VoiceConversationEntry>> = mic.conversation
  val micInputLevel: StateFlow<Float> = mic.inputLevel
  val micIsSending: StateFlow<Boolean> = mic.isSending

  fun setMicEnabled(enabled: Boolean) = mic.setMicEnabled(enabled)

  fun setUseCustomAsr(value: Boolean) {
    asrPrefs.edit().putBoolean("asr.useCustom", value).apply()
    _useCustomAsr.value = value
    if (value) {
      asrPrefs.edit().putBoolean("asr.useIdentity", false).apply()
      _useIdentityAsr.value = false
    }
    mic.switchAsr()  // restart backend to pick up the new ASR type
  }

  fun setUseIdentityAsr() {
    asrPrefs.edit()
      .putBoolean("asr.useIdentity", true)
      .putBoolean("asr.useCustom", false)
      .apply()
    _useIdentityAsr.value = true
    _useCustomAsr.value = false
    mic.switchAsr()
  }

  fun setKwsEnabled(value: Boolean) {
    asrPrefs.edit().putBoolean("asr.kwsEnabled", value).apply()
    _kwsEnabled.value = value
    if (value) mic.startKws() else mic.stopKws()
  }

  fun setKwsGreeting(value: String) {
    asrPrefs.edit().putString("asr.kwsGreeting", value).apply()
    _kwsGreeting.value = value
    kwsTtsPlayer.updateGreeting(value)
  }

  fun previewKwsGreeting(text: String) {
    kwsTtsPlayer.playText(text)
  }

  fun setKwsTitle(value: String) {
    asrPrefs.edit().putString("asr.kwsTitle", value).apply()
    _kwsTitle.value = value
    kwsTtsPlayer.updateRetryPhrase("$value，${_kwsRetryPhrase.value}")
    kwsTtsPlayer.updateSuccessPhrase("$value，${_kwsSuccessPhrase.value}")
  }

  fun setKwsRetryPhrase(value: String) {
    asrPrefs.edit().putString("asr.kwsRetryPhrase", value).apply()
    _kwsRetryPhrase.value = value
    kwsTtsPlayer.updateRetryPhrase("${_kwsTitle.value}，$value")
  }

  fun setKwsSuccessPhrase(value: String) {
    asrPrefs.edit().putString("asr.kwsSuccessPhrase", value).apply()
    _kwsSuccessPhrase.value = value
    kwsTtsPlayer.updateSuccessPhrase("${_kwsTitle.value}，$value")
  }

  fun setKwsAckPhrase(value: String) {
    asrPrefs.edit().putString("asr.kwsAckPhrase", value).apply()
    _kwsAckPhrase.value = value
  }

  // --- Internal ---
  private var webSocket: okhttp3.WebSocket? = null
  private var streamingMsgId: String? = null
  // Tracks deliberate disconnects so unexpected drops trigger reconnect
  @Volatile private var wasUserDisconnect = false
  // When true, a reconnect is already in flight — don't transition to Idle on disconnect
  @Volatile private var pendingReconnect = false
  // Auto-reconnect state
  private var reconnectJob: Job? = null
  private val _reconnectAttempt = MutableStateFlow(0)
  val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()
  private val maxReconnectAttempts = 8
  private val reconnectBaseDelayMs = 2_000L

  // ---------------------------------------------------------------------------
  // Persisted setters
  // ---------------------------------------------------------------------------
  fun setHost(value: String) {
    host.value = value
    prefs.edit().putString("host", value).apply()
  }

  fun setPort(value: Int) {
    port.value = value
    prefs.edit().putInt("port", value).apply()
  }

  fun setUseProxy(value: Boolean) {
    useProxy.value = value
    prefs.edit().putBoolean("use_proxy", value).apply()
  }

  fun setProxyPort(value: Int) {
    proxyPort.value = value
    prefs.edit().putInt("proxy_port", value).apply()
  }

  // ---------------------------------------------------------------------------
  // Step 1: health check → decide if pairing needed
  // ---------------------------------------------------------------------------
  fun connect() {
    if (useProxy.value) {
      // Proxy is transparent: skip health-check and pairing, connect directly
      viewModelScope.launch(Dispatchers.IO) {
        _state.value = ZcState.Connecting
        _errorText.value = null
        openWebSocket()
      }
      return
    }
    val currentToken = _token.value.orEmpty().ifBlank { tokenInput.value.trim().ifEmpty { null } }
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = ZcState.HealthChecking
      _errorText.value = null
      try {
        val health = session.health(host.value, port.value)
        // mirrors chat.py: if require_pairing and not token
        if (health.requirePairing && currentToken == null) {
          _state.value = ZcState.NeedsPairing
        } else {
          if (currentToken != null) _token.value = currentToken
          fetchAndShowSessionPicker()
        }
      } catch (e: Exception) {
        _state.value = ZcState.Error
        _errorText.value = e.message ?: "Cannot reach gateway"
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Step 2: pair with one-time code
  // ---------------------------------------------------------------------------
  fun pair() {
    val code = pairCode.value.trim()
    if (code.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = ZcState.Pairing
      _errorText.value = null
      try {
        val tok = session.pair(host.value, port.value, code)
        _token.value = tok
        prefs.edit().putString("token", tok).apply()
        fetchAndShowSessionPicker()
      } catch (e: Exception) {
        _state.value = ZcState.NeedsPairing
        _errorText.value = e.message ?: "Pairing failed"
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Step 2b: fetch session list → show picker if history exists
  // ---------------------------------------------------------------------------
  private fun fetchAndShowSessionPicker() {
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = ZcState.Connecting
      try {
        val sessions = session.fetchSessions(host.value, port.value, _token.value)
        if (sessions.isEmpty()) {
          openWebSocket()
        } else {
          _sessionPicker.value = ZcSessionPickerState.Choosing(sessions)
          // openWebSocket() will be called from pickNewSession() or pickRestoreSession()
        }
      } catch (_: Exception) {
        // Session API unavailable (older server version) — connect directly
        openWebSocket()
      }
    }
  }

  /** User chose to start a brand-new session. */
  fun pickNewSession() {
    _sessionPicker.value = ZcSessionPickerState.Hidden
    openWebSocket()
  }

  /** User chose to restore an old session — load its messages then open the WS. */
  fun pickRestoreSession(info: ZcSessionInfo) {
    viewModelScope.launch(Dispatchers.IO) {
      _sessionPicker.value = ZcSessionPickerState.Restoring
      try {
        val msgs = session.fetchSessionMessages(host.value, port.value, _token.value, info.id)
        sessionId = info.id
        if (msgs.isNotEmpty()) _messages.value = msgs
      } catch (_: Exception) {
        // Failed to load history — keep the new sessionId and start fresh
      }
      _sessionPicker.value = ZcSessionPickerState.Hidden
      openWebSocket()
    }
  }

  // ---------------------------------------------------------------------------
  // Step 3: open WebSocket to /ws/chat
  // ---------------------------------------------------------------------------
  private fun openWebSocket() {
    _state.value = ZcState.Connecting
    webSocket = if (useProxy.value) {
      // Proxy mode: clawproxy acts as a transparent ZeroClaw gateway —
      // same /ws/chat endpoint, proxy port, no token needed.
      session.connectViaProxy(host.value, proxyPort.value, sessionId) { event ->
        handleEvent(event)
      }
    } else {
      session.connectChat(host.value, port.value, _token.value, sessionId) { event ->
        handleEvent(event)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Event handler
  // ---------------------------------------------------------------------------
  private fun handleEvent(event: ZcEvent) {
    when (event) {
      is ZcEvent.Connected -> {
        wasUserDisconnect = false
        _reconnectAttempt.value = 0
        reconnectJob?.cancel()
        reconnectJob = null
        _errorText.value = null
        _state.value = ZcState.Connected
        mic.onGatewayConnectionChanged(true)
      }

      is ZcEvent.Chunk -> {
        val id =
          streamingMsgId
            ?: run {
              val newId = UUID.randomUUID().toString()
              streamingMsgId = newId
              _messages.update {
                it + ZcChatMessage(id = newId, role = "assistant", content = "", isStreaming = true)
              }
              newId
            }
        _messages.update { msgs ->
          msgs.map { if (it.id == id) it.copy(content = it.content + event.content) else it }
        }
        // Accumulate chunk in the current streaming message text and forward to voice UI
        val accum = _messages.value.find { it.id == streamingMsgId }?.content ?: event.content
        mic.onExternalAssistantDelta(accum)
      }

      is ZcEvent.Done -> {
        val id = streamingMsgId
        if (id != null) {
          _messages.update { msgs ->
            msgs.map {
              if (it.id == id) {
                it.copy(
                  content = event.fullResponse.ifEmpty { it.content },
                  isStreaming = false,
                )
              } else {
                it
              }
            }
          }
          val finalText = event.fullResponse.ifEmpty {
            _messages.value.find { it.id == id }?.content ?: ""
          }
          mic.onExternalAssistantComplete(finalText)
          streamingMsgId = null
        } else if (event.fullResponse.isNotEmpty()) {
          _messages.update { it + ZcChatMessage(role = "assistant", content = event.fullResponse) }
          mic.onExternalAssistantComplete(event.fullResponse)
        }
      }

      is ZcEvent.Message -> {
        streamingMsgId = null
        _messages.update { it + ZcChatMessage(role = "assistant", content = event.content) }
      }

      is ZcEvent.ToolCall -> {
        _messages.update {
          it + ZcChatMessage(role = "tool_call", content = "${event.name}(${event.args})")
        }
      }

      is ZcEvent.ToolResult -> {
        _messages.update { it + ZcChatMessage(role = "tool_result", content = event.output) }
      }

      is ZcEvent.Unauthorized -> {
        // Token rejected — clear it and force re-pairing
        _token.value = null
        prefs.edit().remove("token").apply()
        _errorText.value = "Token expired or invalid — please re-pair"
        _state.value = ZcState.NeedsPairing
        streamingMsgId = null
      }

      is ZcEvent.Errored -> {
        // Always surface the error so user can see it (not just as a chat bubble)
        _errorText.value = event.message
        if (_state.value == ZcState.Connected) {
          // Already chatting — also show inline in the message list
          _messages.update { it + ZcChatMessage(role = "system_error", content = event.message) }
        }
        streamingMsgId = null
      }

      is ZcEvent.Disconnected -> {
        mic.onGatewayConnectionChanged(false)
        // Seal any partial streaming bubble so the user can see what arrived
        val sid = streamingMsgId
        if (sid != null) {
          _messages.update { msgs ->
            msgs.map { if (it.id == sid) it.copy(isStreaming = false) else it }
          }
          streamingMsgId = null
        }
        webSocket = null
        val intentional = wasUserDisconnect
        val isReconnect = pendingReconnect
        wasUserDisconnect = false
        pendingReconnect = false
        when {
          intentional && !isReconnect -> {
            // User pressed Disconnect — go back to Idle
            _errorText.value = null
            _state.value = ZcState.Idle
          }
          intentional && isReconnect -> {
            // stopStreaming() reconnect — openWebSocket() already called
          }
          _state.value == ZcState.NeedsPairing -> {
            // Unauthorized already handled; don't overwrite NeedsPairing
          }
          else -> {
            // Unexpected drop — auto-reconnect with backoff
            scheduleReconnect()
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Auto-reconnect with exponential backoff
  // ---------------------------------------------------------------------------
  private fun scheduleReconnect() {
    if (_reconnectAttempt.value >= maxReconnectAttempts) {
      _state.value = ZcState.Error
      _errorText.value = "Connection lost after $maxReconnectAttempts attempts"
      _reconnectAttempt.value = 0
      return
    }
    _state.value = ZcState.Reconnecting
    val delayMs = minOf(reconnectBaseDelayMs * (1L shl _reconnectAttempt.value), 30_000L)
    reconnectJob?.cancel()
    reconnectJob = viewModelScope.launch(Dispatchers.IO) {
      delay(delayMs)
      _reconnectAttempt.value++
      try {
        openWebSocket()
      } catch (e: Exception) {
        scheduleReconnect()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Send user message
  // ---------------------------------------------------------------------------
  fun sendMessage(text: String, attachments: List<ZcImageAttachment> = emptyList()) {
    val ws = webSocket ?: return
    val content = text.trim()
    if (content.isEmpty() && attachments.isEmpty()) return
    _messages.update { it + ZcChatMessage(role = "user", content = content, attachments = attachments) }
    val sent = ws.send(buildJsonPayload(content, attachments))
    if (!sent) {
      _messages.update {
        it + ZcChatMessage(role = "system_error", content = "Failed to send — connection may have dropped")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------
  fun disconnect() {
    reconnectJob?.cancel()
    reconnectJob = null
    _reconnectAttempt.value = 0
    _sessionPicker.value = ZcSessionPickerState.Hidden
    wasUserDisconnect = true
    webSocket?.close(1000, "User disconnected")
    webSocket = null
    sessionId = UUID.randomUUID().toString()  // fresh session on next connect
    _state.value = ZcState.Idle
    _errorText.value = null
    streamingMsgId = null
  }

  /** Abort the current streaming response and silently reconnect so the user can keep chatting. */
  fun stopStreaming() {
    // Finalize any partial stream immediately
    val id = streamingMsgId
    if (id != null) {
      _messages.update { msgs ->
        msgs.map { if (it.id == id) it.copy(isStreaming = false) else it }
      }
      streamingMsgId = null
    }
    // Close current WS silently and reconnect in the background
    wasUserDisconnect = true
    pendingReconnect = true
    webSocket?.close(1000, "User stopped")
    webSocket = null
    openWebSocket()
  }

  /** Remove the last assistant response(s) and resend the last user message. */
  fun regenerateLastMessage() {
    if (_state.value != ZcState.Connected) return
    val ws = webSocket ?: return
    val msgs = _messages.value
    val lastUserIdx = msgs.indexOfLast { it.role == "user" }
    if (lastUserIdx < 0) return
    val lastUser = msgs[lastUserIdx]
    // Drop everything after the last user message
    _messages.value = msgs.take(lastUserIdx + 1)
    streamingMsgId = null
    val sent = ws.send(buildJsonPayload(lastUser.content, lastUser.attachments))
    if (!sent) {
      _messages.update {
        it + ZcChatMessage(role = "system_error", content = "Failed to regenerate — connection may have dropped")
      }
    }
  }

  fun clearMessages() {
    _messages.value = emptyList()
    streamingMsgId = null
  }

  fun clearAndReset() {
    disconnect()
    _messages.value = emptyList()
    _token.value = null
    tokenInput.value = ""
    pairCode.value = ""
    prefs.edit().remove("token").apply()
  }

  override fun onCleared() {
    super.onCleared()
    mic.setMicEnabled(false)
    reconnectJob?.cancel()
    webSocket?.cancel()
    webSocket = null
    kwsTtsPlayer.release()
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  private fun buildJsonPayload(content: String, attachments: List<ZcImageAttachment> = emptyList()): String {
    val obj = JSONObject()
    obj.put("type", "message")
    obj.put("content", content)
    if (attachments.isNotEmpty()) {
      val arr = JSONArray()
      for (att in attachments) {
        val a = JSONObject()
        a.put("type", "image")
        a.put("mimeType", att.mimeType)
        a.put("fileName", att.fileName)
        a.put("data", att.base64)
        arr.put(a)
      }
      obj.put("attachments", arr)
    }
    return obj.toString()
  }
}
