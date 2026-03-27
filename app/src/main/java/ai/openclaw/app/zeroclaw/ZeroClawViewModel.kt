package ai.openclaw.app.zeroclaw

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
// ViewModel
// ---------------------------------------------------------------------------
class ZeroClawViewModel(app: Application) : AndroidViewModel(app) {

  private val prefs = app.getSharedPreferences("zeroclaw.direct", Context.MODE_PRIVATE)
  private val client = OkHttpClient.Builder()
    .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
    .build()
  private val session = ZeroClawSession(client)

  // --- Persisted connection settings ---
  val host = MutableStateFlow(prefs.getString("host", "10.0.2.2") ?: "10.0.2.2")
  val port = MutableStateFlow(prefs.getInt("port", 42617))
  val pairCode = MutableStateFlow("")
  val tokenInput = MutableStateFlow("")

  private val _token = MutableStateFlow<String?>(prefs.getString("token", null))
  val token: StateFlow<String?> = _token.asStateFlow()

  // --- UI state ---
  private val _state = MutableStateFlow(ZcState.Idle)
  val state: StateFlow<ZcState> = _state.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private val _messages = MutableStateFlow<List<ZcChatMessage>>(emptyList())
  val messages: StateFlow<List<ZcChatMessage>> = _messages.asStateFlow()

  // --- Internal ---
  private var webSocket: okhttp3.WebSocket? = null
  private var streamingMsgId: String? = null
  // Tracks deliberate disconnects so unexpected drops are shown as errors
  @Volatile private var wasUserDisconnect = false
  // When true, a reconnect is already in flight — don't transition to Idle on disconnect
  @Volatile private var pendingReconnect = false

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

  // ---------------------------------------------------------------------------
  // Step 1: health check → decide if pairing needed
  // ---------------------------------------------------------------------------
  fun connect() {
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
          openWebSocket()
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
        openWebSocket()
      } catch (e: Exception) {
        _state.value = ZcState.NeedsPairing
        _errorText.value = e.message ?: "Pairing failed"
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Step 3: open WebSocket to /ws/chat
  // ---------------------------------------------------------------------------
  private fun openWebSocket() {
    _state.value = ZcState.Connecting
    webSocket = session.connectChat(host.value, port.value, _token.value) { event ->
      handleEvent(event)
    }
  }

  // ---------------------------------------------------------------------------
  // Event handler
  // ---------------------------------------------------------------------------
  private fun handleEvent(event: ZcEvent) {
    when (event) {
      is ZcEvent.Connected -> {
        wasUserDisconnect = false
        _errorText.value = null
        _state.value = ZcState.Connected
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
          streamingMsgId = null
        } else if (event.fullResponse.isNotEmpty()) {
          _messages.update { it + ZcChatMessage(role = "assistant", content = event.fullResponse) }
        }
      }

      is ZcEvent.Message -> {
        streamingMsgId = null
        _messages.update { it + ZcChatMessage(role = "assistant", content = event.content) }
      }

      is ZcEvent.ToolCall ->
        _messages.update {
          it + ZcChatMessage(role = "tool_call", content = "${event.name}(${event.args})")
        }

      is ZcEvent.ToolResult ->
        _messages.update { it + ZcChatMessage(role = "tool_result", content = event.output) }

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
        streamingMsgId = null
        webSocket = null
        if (wasUserDisconnect) {
          _errorText.value = null
          if (!pendingReconnect) {
            // Deliberate disconnect (not a stop-reconnect) — go back to setup
            _state.value = ZcState.Idle
          }
        } else {
          // Unexpected — go to Error so user sees what happened
          _state.value = ZcState.Error
          if (_errorText.value == null) {
            _errorText.value = "Disconnected from server"
          }
        }
        wasUserDisconnect = false
        pendingReconnect = false
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
    wasUserDisconnect = true
    webSocket?.close(1000, "User disconnected")
    webSocket = null
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
    webSocket?.cancel()
    webSocket = null
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
