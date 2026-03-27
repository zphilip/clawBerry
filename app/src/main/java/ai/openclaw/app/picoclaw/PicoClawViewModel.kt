package ai.openclaw.app.picoclaw

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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// State machine
// ---------------------------------------------------------------------------
enum class PcState {
    Idle,
    FetchingToken, // Mode 1 only: fetching token from web backend
    Connecting,    // WS handshake in progress
    Connected,
    Error,
}

enum class PcMode { WebBackend, Direct }

// ---------------------------------------------------------------------------
// Attachment model (mirrors ZeroClaw's for UI parity)
// ---------------------------------------------------------------------------
data class PcImageAttachment(
    val mimeType: String,
    val fileName: String,
    val base64: String,
)

// ---------------------------------------------------------------------------
// Chat message model
// ---------------------------------------------------------------------------
data class PcChatMessage(
    val id: String = UUID.randomUUID().toString(),
    /** "user" | "assistant" | "typing" | "system_error" */
    val role: String,
    val content: String,
    /** Server-assigned message_id for in-place message.update tracking */
    val serverMessageId: String? = null,
    val attachments: List<PcImageAttachment> = emptyList(),
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------
class PicoClawViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("picoclaw.direct", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val session = PicoClawSession(client)

    // ── Persisted settings ────────────────────────────────────────────────────
    val host = MutableStateFlow(prefs.getString("host", "127.0.0.1") ?: "127.0.0.1")
    val webPort = MutableStateFlow(prefs.getInt("webPort", 18800))
    val gatewayPort = MutableStateFlow(prefs.getInt("gatewayPort", 18790))
    val mode = MutableStateFlow(
        if (prefs.getString("mode", "web") == "direct") PcMode.Direct else PcMode.WebBackend
    )
    val tokenInput = MutableStateFlow(prefs.getString("tokenInput", "") ?: "")

    private val _token = MutableStateFlow<String?>(prefs.getString("token", null))
    val token: StateFlow<String?> = _token.asStateFlow()

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(PcState.Idle)
    val state: StateFlow<PcState> = _state.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _messages = MutableStateFlow<List<PcChatMessage>>(emptyList())
    val messages: StateFlow<List<PcChatMessage>> = _messages.asStateFlow()

    // ── Internal ──────────────────────────────────────────────────────────────
    private var webSocket: okhttp3.WebSocket? = null
    @Volatile private var wasUserDisconnect = false
    // When true, a reconnect is already in flight — don't transition to Idle on disconnect
    @Volatile private var pendingReconnect = false
    private val msgCounter = AtomicInteger(0)

    // ── Persisted setters ─────────────────────────────────────────────────────
    fun setHost(v: String) {
        host.value = v
        prefs.edit().putString("host", v).apply()
    }

    fun setWebPort(v: Int) {
        webPort.value = v
        prefs.edit().putInt("webPort", v).apply()
    }

    fun setGatewayPort(v: Int) {
        gatewayPort.value = v
        prefs.edit().putInt("gatewayPort", v).apply()
    }

    fun setMode(m: PcMode) {
        mode.value = m
        prefs.edit().putString("mode", if (m == PcMode.Direct) "direct" else "web").apply()
    }

    fun setTokenInput(v: String) {
        tokenInput.value = v
        prefs.edit().putString("tokenInput", v).apply()
    }

    // ── Connect ───────────────────────────────────────────────────────────────
    fun connect() {
        val currentMode = mode.value
        viewModelScope.launch(Dispatchers.IO) {
            _errorText.value = null
            try {
                val wsUrl: String
                val effectiveToken: String

                if (currentMode == PcMode.WebBackend) {
                    _state.value = PcState.FetchingToken
                    val resp = session.fetchToken(host.value, webPort.value)
                    if (!resp.enabled) {
                        _state.value = PcState.Error
                        _errorText.value = "PicoClaw channel is not enabled on this gateway"
                        return@launch
                    }
                    effectiveToken = resp.token
                    wsUrl = normalizeWsUrl(resp.wsUrl, host.value)
                    // Persist the fetched token for reference
                    _token.value = effectiveToken
                    prefs.edit().putString("token", effectiveToken).apply()
                } else {
                    // Direct mode — user supplies token
                    effectiveToken = tokenInput.value.trim().ifEmpty { _token.value ?: "" }
                    if (effectiveToken.isBlank()) {
                        _state.value = PcState.Error
                        _errorText.value = "Token is required in Direct mode"
                        return@launch
                    }
                    wsUrl = "ws://${host.value}:${gatewayPort.value}/pico/ws"
                }

                _state.value = PcState.Connecting
                openWebSocket(wsUrl = wsUrl, token = effectiveToken)
            } catch (e: Exception) {
                _state.value = PcState.Error
                _errorText.value = e.message ?: "Connection failed"
            }
        }
    }

    private fun openWebSocket(wsUrl: String, token: String) {
        webSocket = session.connectWs(wsUrl = wsUrl, token = token) { event ->
            handleEvent(event)
        }
    }

    // ── Event handler ─────────────────────────────────────────────────────────
    private fun handleEvent(event: PcEvent) {
        when (event) {
            is PcEvent.Connected -> {
                wasUserDisconnect = false
                _errorText.value = null
                _state.value = PcState.Connected
            }

            is PcEvent.TypingStart -> {
                _messages.update { msgs ->
                    if (msgs.none { it.role == "typing" }) {
                        msgs + PcChatMessage(role = "typing", content = "")
                    } else msgs
                }
            }

            is PcEvent.TypingStop -> {
                _messages.update { msgs -> msgs.filter { it.role != "typing" } }
            }

            is PcEvent.MessageCreate -> {
                _messages.update { msgs ->
                    msgs.filter { it.role != "typing" } +
                        PcChatMessage(
                            role = "assistant",
                            content = event.content,
                            serverMessageId = event.messageId.ifBlank { null },
                        )
                }
            }

            is PcEvent.MessageUpdate -> {
                _messages.update { msgs ->
                    val idx = msgs.indexOfFirst { it.serverMessageId == event.messageId }
                    if (idx >= 0) {
                        // In-place update (streaming / correction)
                        msgs.toMutableList().also { list ->
                            list[idx] = list[idx].copy(content = event.content)
                        }
                    } else {
                        // Unknown messageId — append as new assistant message
                        msgs.filter { it.role != "typing" } +
                            PcChatMessage(
                                role = "assistant",
                                content = event.content,
                                serverMessageId = event.messageId.ifBlank { null },
                            )
                    }
                }
            }

            is PcEvent.Errored -> {
                _errorText.value = event.message
                if (_state.value == PcState.Connected) {
                    _messages.update {
                        it + PcChatMessage(role = "system_error", content = event.message)
                    }
                }
            }

            is PcEvent.Disconnected -> {
                webSocket = null
                _messages.update { msgs -> msgs.filter { it.role != "typing" } }
                if (wasUserDisconnect) {
                    _errorText.value = null
                    if (!pendingReconnect) {
                        _state.value = PcState.Idle
                    }
                } else {
                    _state.value = PcState.Error
                    if (_errorText.value == null) {
                        _errorText.value = "Disconnected from server"
                    }
                }
                wasUserDisconnect = false
                pendingReconnect = false
            }
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────
    fun sendMessage(text: String, attachments: List<PcImageAttachment> = emptyList()) {
        val ws = webSocket ?: return
        val content = text.trim()
        if (content.isEmpty() && attachments.isEmpty()) return
        _messages.update { it + PcChatMessage(role = "user", content = content, attachments = attachments) }
        val counter = msgCounter.incrementAndGet()
        val msgId = "msg-$counter-${System.currentTimeMillis()}"
        val payloadObj = JSONObject().put("content", content)
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            for (att in attachments) {
                arr.put(JSONObject().apply {
                    put("type", "image")
                    put("mimeType", att.mimeType)
                    put("fileName", att.fileName)
                    put("data", att.base64)
                })
            }
            payloadObj.put("attachments", arr)
        }
        val payload = JSONObject().apply {
            put("type", "message.send")
            put("id", msgId)
            put("payload", payloadObj)
        }.toString()
        val sent = ws.send(payload)
        if (!sent) {
            _messages.update {
                it + PcChatMessage(
                    role = "system_error",
                    content = "Failed to send — connection may have dropped",
                )
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun disconnect() {
        wasUserDisconnect = true
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _state.value = PcState.Idle
        _errorText.value = null
        _messages.update { msgs -> msgs.filter { it.role != "typing" } }
    }

    /** Abort the current streaming response and silently reconnect so the user can keep chatting. */
    fun stopStreaming() {
        // Remove any typing/streaming indicator
        _messages.update { msgs -> msgs.filter { it.role != "typing" } }
        // Close current WS silently and reconnect in the background
        wasUserDisconnect = true
        pendingReconnect = true
        webSocket?.close(1000, "User stopped")
        webSocket = null
        connect()
    }

    /** Remove the last assistant response(s) and resend the last user message. */
    fun regenerateLastMessage() {
        if (_state.value != PcState.Connected) return
        val msgs = _messages.value
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return
        val lastUser = msgs[lastUserIdx]
        _messages.value = msgs.take(lastUserIdx + 1)
        sendMessage(lastUser.content, lastUser.attachments)
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun clearAndReset() {
        disconnect()
        _messages.value = emptyList()
        _token.value = null
        tokenInput.value = ""
        prefs.edit().remove("token").remove("tokenInput").apply()
    }

    override fun onCleared() {
        super.onCleared()
        webSocket?.cancel()
        webSocket = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    /**
     * The web-backend may return a ws_url with localhost / 127.0.0.1 which is
     * unreachable from an Android device on LAN.  Replace it with the host the
     * user actually configured.
     */
    private fun normalizeWsUrl(wsUrl: String, configuredHost: String): String =
        wsUrl
            .replace("ws://localhost:", "ws://$configuredHost:")
            .replace("ws://127.0.0.1:", "ws://$configuredHost:")
            .replace("ws://0.0.0.0:", "ws://$configuredHost:")
}
