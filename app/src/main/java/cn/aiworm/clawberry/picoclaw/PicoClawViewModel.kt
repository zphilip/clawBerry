package clawberry.aiworm.cn.picoclaw

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log
import clawberry.aiworm.cn.voice.KwsManager
import clawberry.aiworm.cn.voice.KwsTtsPlayer
import clawberry.aiworm.cn.voice.MicCaptureManager
import clawberry.aiworm.cn.voice.ProxyTtsAudioPlayer
import clawberry.aiworm.cn.voice.VoiceConversationEntry

// ---------------------------------------------------------------------------
// State machine
// ---------------------------------------------------------------------------
enum class PcState {
    Idle,
    FetchingToken,  // Mode 1 only: fetching token from web backend
    Connecting,     // WS handshake in progress
    Connected,
    Reconnecting,   // Auto-reconnect in progress (after unexpected disconnect)
    Error,
}

enum class PcMode { WebBackend, Direct, Proxy }

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
private const val DEFAULT_VOICE_TTS_HINT = "（语音模式：请用简洁口语回答，避免Markdown格式和特殊符号）"

class PicoClawViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("picoclaw.direct", Context.MODE_PRIVATE)
    private val asrPrefs = app.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // no read timeout — WS stays open indefinitely
        .build()
    private val session = PicoClawSession(client)

    // ── Persisted settings ────────────────────────────────────────────────────
    val host = MutableStateFlow(prefs.getString("host", "127.0.0.1") ?: "127.0.0.1")
    val webPort = MutableStateFlow(prefs.getInt("webPort", 18800))
    val gatewayPort = MutableStateFlow(prefs.getInt("gatewayPort", 18790))
    val proxyPort   = MutableStateFlow(prefs.getInt("proxyPort", 18780))
    val mode = MutableStateFlow(
        when (prefs.getString("mode", "web")) {
            "direct" -> PcMode.Direct
            "proxy"  -> PcMode.Proxy
            else     -> PcMode.WebBackend
        }
    )
    val tokenInput = MutableStateFlow(prefs.getString("tokenInput", "") ?: "")

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

    private val _voiceThinkingPhrase = MutableStateFlow(
        asrPrefs.getString("asr.voiceThinkingPhrase", "让我考虑下如何完成任务") ?: "让我考虑下如何完成任务",
    )
    val voiceThinkingPhrase: StateFlow<String> = _voiceThinkingPhrase.asStateFlow()

    private val _voiceThinkingEnabled = MutableStateFlow(
        asrPrefs.getBoolean("asr.voiceThinkingEnabled", true),
    )
    val voiceThinkingEnabled: StateFlow<Boolean> = _voiceThinkingEnabled.asStateFlow()

    private val _voiceToolCallsPhrase = MutableStateFlow(
        asrPrefs.getString("asr.voiceToolCallsPhrase", "任务在执行中，还需一些时间") ?: "任务在执行中，还需一些时间",
    )
    val voiceToolCallsPhrase: StateFlow<String> = _voiceToolCallsPhrase.asStateFlow()

    private val _voiceToolCallsEnabled = MutableStateFlow(
        asrPrefs.getBoolean("asr.voiceToolCallsEnabled", true),
    )
    val voiceToolCallsEnabled: StateFlow<Boolean> = _voiceToolCallsEnabled.asStateFlow()

    private val _voiceTtsHint = MutableStateFlow(
        asrPrefs.getString("asr.voiceTtsHint", DEFAULT_VOICE_TTS_HINT) ?: DEFAULT_VOICE_TTS_HINT,
    )
    val voiceTtsHint: StateFlow<String> = _voiceTtsHint.asStateFlow()

    init {
        // Sync globalIsRegistered from persisted prefs so voice chip shows correct state
        // even before the user opens the Settings tab (which creates SpeakerRegistrationManager).
        clawberry.aiworm.cn.voice.SpeakerRegistrationManager.loadRegistrationState(app.applicationContext)
    }

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

    // ── Voice (always-listening mic) ──────────────────────────────────────────
    private val kwsTtsPlayer = KwsTtsPlayer(app.applicationContext, viewModelScope).also {
        it.updateGreeting(_kwsGreeting.value)
        it.updateRetryPhrase("${_kwsTitle.value}，${_kwsRetryPhrase.value}")
        it.updateSuccessPhrase("${_kwsTitle.value}，${_kwsSuccessPhrase.value}")
        it.updateThinkingPhrase(_voiceThinkingPhrase.value)
        it.updateToolCallsPhrase(_voiceToolCallsPhrase.value)
        it.init()
    }
    private val proxyTtsAudioPlayer = ProxyTtsAudioPlayer(app.applicationContext)
    // TTS streaming queue — serialises back-to-back chunks for gapless playback
    private val ttsQueue = Channel<PcEvent.TtsAudio>(Channel.UNLIMITED)
    @Volatile private var ttsJob: Job? = null
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
                android.util.Log.i("PicoClawVM", "KWS onKeyword: '$keyword' isPipelineActive=${mic.isPipelineActive}")
                if (mic.isPipelineActive) {
                    android.util.Log.i("PicoClawVM", "KWS onKeyword → pipeline BUSY → setPendingKwsActivation")
                    kwsTtsPlayer.playText("主人，我正在处理您的上一条指令，完成后即为您服务")
                    mic.setPendingKwsActivation()
                } else {
                    android.util.Log.i("PicoClawVM", "KWS onKeyword → pipeline IDLE → playGreeting → notifyKeyword")
                    // Stop any in-progress thinking/tool-calls prompt immediately.
                    // playGreeting() shares promptPlayMutex with voice prompts; without
                    // interrupting first, the greeting (and notifyKeyword) would be blocked
                    // for the full remaining prompt duration.
                    voicePromptJob?.cancel()
                    voicePromptJob = null
                    kwsTtsPlayer.stopVoicePrompt()
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
            onRunIdKnown(java.util.UUID.randomUUID().toString())
            val hint = _voiceTtsHint.value.trim()
            val textToSend = if (hint.isNotEmpty()) "$message\n\n$hint" else message
            sendMessage(textToSend, isVoiceInitiated = true)
            null  // no runId tracking — responses come via onExternalAssistant*
        },
        speakAssistantReply = {},   // no TTS for Pico voice tab
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

    fun setVoiceThinkingPhrase(value: String) {
        asrPrefs.edit().putString("asr.voiceThinkingPhrase", value).apply()
        _voiceThinkingPhrase.value = value
        kwsTtsPlayer.updateThinkingPhrase(value)
    }

    fun setVoiceThinkingEnabled(value: Boolean) {
        asrPrefs.edit().putBoolean("asr.voiceThinkingEnabled", value).apply()
        _voiceThinkingEnabled.value = value
    }

    fun setVoiceToolCallsPhrase(value: String) {
        asrPrefs.edit().putString("asr.voiceToolCallsPhrase", value).apply()
        _voiceToolCallsPhrase.value = value
        kwsTtsPlayer.updateToolCallsPhrase(value)
    }

    fun setVoiceToolCallsEnabled(value: Boolean) {
        asrPrefs.edit().putBoolean("asr.voiceToolCallsEnabled", value).apply()
        _voiceToolCallsEnabled.value = value
    }

    fun setVoiceTtsHint(value: String) {
        asrPrefs.edit().putString("asr.voiceTtsHint", value).apply()
        _voiceTtsHint.value = value
    }


    // ── Auto-reconnect ────────────────────────────────────────────────────────
    private var reconnectJob: Job? = null
    @Volatile private var reconnectAttempt = 0
    private val maxReconnectAttempts = 8
    private val reconnectBaseDelayMs = 2_000L   // 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s
    // Cached to avoid re-fetching settings mid-reconnect
    @Volatile private var cachedWsUrl: String? = null
    @Volatile private var cachedToken: String? = null
    @Volatile private var typingActive = false
    /** Tracks the active voice-prompt coroutine so a new prompt can cancel the previous one. */
    private var voicePromptJob: Job? = null
    /** Single-lane gate: only one prompt (thinking/tool-calls) can run at a time. */
    private val voicePromptGate = Semaphore(1)
    /** True while the TTS stream is actively playing; blocks late voice-prompt triggers. */
    @Volatile private var ttsIsActive = false
    /** True once at least one proxy TTS chunk was seen for the current assistant turn. */
    @Volatile private var sawProxyTtsChunkThisTurn = false
    /** True when the current assistant turn was triggered by voice (KWS/ASR) input, not typed text. */
    @Volatile private var lastTurnWasVoice = false
    /** Final assistant text buffered until proxy TTS reaches isFinal=true. */
    @Volatile private var pendingAssistantFinalText: String? = null
    /** Timeout fallback in case a turn was marked voice but no TTS chunk arrives. */
    private var pendingCompletionFallbackJob: Job? = null
    /** Message IDs of assistant messages created in the current typing round.
     *  Only updates to these IDs are forwarded to the voice panel. */
    private val currentRoundMessageIds = mutableSetOf<String>()

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

    fun setProxyPort(v: Int) {
        proxyPort.value = v
        prefs.edit().putInt("proxyPort", v).apply()
    }

    fun setMode(m: PcMode) {
        mode.value = m
        prefs.edit().putString("mode", when (m) {
            PcMode.Direct     -> "direct"
            PcMode.Proxy      -> "proxy"
            PcMode.WebBackend -> "web"
        }).apply()
    }

    fun setTokenInput(v: String) {
        tokenInput.value = v
        prefs.edit().putString("tokenInput", v).apply()
    }

    // ── Connect ───────────────────────────────────────────────────────────────
    fun connect() {
        reconnectJob?.cancel()
        reconnectAttempt = 0
        val currentMode = mode.value
        viewModelScope.launch(Dispatchers.IO) {
            _errorText.value = null
            try {
                val wsUrl: String
                val effectiveToken: String

                if (currentMode == PcMode.Proxy) {
                    cachedWsUrl = "ws://${host.value}:${proxyPort.value}/pico/ws"
                    cachedToken = ""
                    _state.value = PcState.Connecting
                    openProxyWebSocket()
                    return@launch
                }

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

                // Cache for auto-reconnect
                cachedWsUrl = wsUrl
                cachedToken = effectiveToken
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

    // Proxy shortcut: clawproxy acts as a transparent PicoClaw gateway —
    // same /pico/ws endpoint, proxy port, no token needed.
    private fun openProxyWebSocket() {
        webSocket = session.connectViaProxy(host.value, proxyPort.value) { event ->
            handleEvent(event)
        }
    }

    // ── Event handler ─────────────────────────────────────────────────────────
    private fun handleEvent(event: PcEvent) {
        when (event) {
            is PcEvent.Connected -> {
                reconnectAttempt = 0
                reconnectJob = null
                wasUserDisconnect = false
                typingActive = false
                _errorText.value = null
                _state.value = PcState.Connected
                mic.onGatewayConnectionChanged(true)
            }

            is PcEvent.TypingStart -> {
                typingActive = true
                currentRoundMessageIds.clear()
                sawProxyTtsChunkThisTurn = false
                pendingAssistantFinalText = null
                pendingCompletionFallbackJob?.cancel()
                pendingCompletionFallbackJob = null
                voicePromptJob?.cancel()
                kwsTtsPlayer.stopVoicePrompt()
                voicePromptJob = null
                ttsIsActive = false
                Log.d("PicoClaw", "[voice] typing.start — voice state reset")
                _messages.update { msgs ->
                    if (msgs.none { it.role == "typing" }) {
                        msgs + PcChatMessage(role = "typing", content = "")
                    } else msgs
                }
            }

            is PcEvent.TypingStop -> {
                typingActive = false
                _messages.update { msgs -> msgs.filter { it.role != "typing" } }
                // Finalize voice UI only with content from the current round's messages.
                // Ignoring old messages avoids surfacing previous-round content when the
                // server sends message.update events for a carry-over message ID.
                val lastContent = _messages.value.lastOrNull { msg ->
                    msg.role == "assistant" &&
                    (msg.serverMessageId == null || msg.serverMessageId in currentRoundMessageIds)
                }?.content ?: ""
                completeAssistantTurn(lastContent, "typing.stop")
            }

            is PcEvent.MessageCreate -> {
                val msgId = event.messageId.ifBlank { null }
                if (msgId != null) currentRoundMessageIds.add(msgId)
                _messages.update { msgs ->
                    msgs.filter { it.role != "typing" } +
                        PcChatMessage(
                            role = "assistant",
                            content = event.content,
                            serverMessageId = msgId,
                        )
                }
                mic.onExternalAssistantDelta(event.content)
                if (!typingActive) {
                    completeAssistantTurn(event.content, "message.create")
                }
                // Voice prompts for intermediate frames (proxy/TTS mode only, once per round)
                if (mode.value == PcMode.Proxy) {
                    val kind = event.kind
                    val thinkingEnabled = asrPrefs.getBoolean("asr.voiceThinkingEnabled", true)
                    val toolCallsEnabled = asrPrefs.getBoolean("asr.voiceToolCallsEnabled", true)
                    Log.d("PicoClaw", "[voice] message.create kind=${kind ?: "(none)"} ttsActive=$ttsIsActive promptJobActive=${voicePromptJob?.isActive}")
                    when (kind) {
                        "thought", "thinking" -> {
                            if (thinkingEnabled && !ttsIsActive) {
                                Log.d("PicoClaw", "[voice] → triggering thinking prompt")
                                voicePromptJob?.cancel()
                                val gen = kwsTtsPlayer.stopVoicePrompt()
                                voicePromptJob = viewModelScope.launch(Dispatchers.IO) {
                                    voicePromptGate.withPermit {
                                        runCatching { kwsTtsPlayer.playThinkingSuspend(gen) }
                                            .onSuccess { Log.d("PicoClaw", "[voice] thinking prompt done gen=$gen") }
                                            .onFailure { Log.w("PicoClaw", "[voice] thinking prompt failed gen=$gen", it) }
                                    }
                                }
                            } else {
                                Log.d("PicoClaw", "[voice] → thinking prompt skipped (enabled=$thinkingEnabled ttsActive=$ttsIsActive)")
                            }
                        }
                        "tool_calls", "tool-calls" -> {
                            if (toolCallsEnabled && !ttsIsActive) {
                                Log.d("PicoClaw", "[voice] → triggering tool-calls prompt")
                                voicePromptJob?.cancel()
                                val gen = kwsTtsPlayer.stopVoicePrompt()
                                voicePromptJob = viewModelScope.launch(Dispatchers.IO) {
                                    voicePromptGate.withPermit {
                                        runCatching { kwsTtsPlayer.playToolCallsSuspend(gen) }
                                            .onSuccess { Log.d("PicoClaw", "[voice] tool-calls prompt done gen=$gen") }
                                            .onFailure { Log.w("PicoClaw", "[voice] tool-calls prompt failed gen=$gen", it) }
                                    }
                                }
                            } else {
                                Log.d("PicoClaw", "[voice] → tool-calls prompt skipped (enabled=$toolCallsEnabled ttsActive=$ttsIsActive)")
                            }
                        }
                        else -> Log.d("PicoClaw", "[voice] → no prompt for kind=${kind ?: "(none)"}")
                    }
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
                // Only forward to voice panel if this message was created in the current round;
                // updates to carry-over messages from a previous round must not bleed into the
                // new round's voice conversation entry.
                if (event.messageId.isBlank() || event.messageId in currentRoundMessageIds) {
                    mic.onExternalAssistantDelta(event.content)
                    if (!typingActive) {
                        completeAssistantTurn(event.content, "message.update")
                    }
                }
            }

            is PcEvent.TtsAudio -> {
                if (mode.value != PcMode.Proxy) return
                if (!lastTurnWasVoice) return  // text-typed messages must not trigger TTS playback
                sawProxyTtsChunkThisTurn = true
                pendingCompletionFallbackJob?.cancel()
                pendingCompletionFallbackJob = null
                Log.d("PicoClaw", "[tts] chunk received format=${event.format} isFinal=${event.isFinal} b64len=${event.audioBase64.length} (~${event.audioBase64.length * 3 / 4}B) — queuing")
                ttsQueue.trySend(event)
                if (ttsJob?.isActive != true) {
                    // Set ttsIsActive BEFORE launching the async job so that any
                    // MessageCreate events arriving on the same WebSocket thread
                    // before the job's first iteration see ttsIsActive=true and
                    // skip voice-prompt playback.  Without this, a brief window
                    // exists where both the prompt AudioTrack and the proxy-TTS
                    // MediaPlayer/AudioTrack play concurrently.
                    ttsIsActive = true
                    voicePromptJob?.cancel()
                    voicePromptJob = null
                    kwsTtsPlayer.stopVoicePrompt()
                    ttsJob = viewModelScope.launch(Dispatchers.IO) {
                        var paused = false
                        var ttsCompleted = false
                        var chunkIndex = 0
                        try {
                            for (chunk in ttsQueue) {
                                if (!paused) {
                                    Log.d("PicoClaw", "[tts] stream start — pausing mic")
                                    mic.pauseForTts()
                                    paused = true
                                }
                                Log.d("PicoClaw", "[tts] playing chunk #${++chunkIndex} isFinal=${chunk.isFinal} format=${chunk.format} b64len=${chunk.audioBase64.length} (~${chunk.audioBase64.length * 3 / 4}B)")
                                runCatching { proxyTtsAudioPlayer.playRaw(chunk.audioBase64, chunk.format) }
                                    .onFailure { Log.w("PicoClaw", "[tts] playRaw failed on chunk #$chunkIndex", it) }
                                if (chunk.isFinal) { ttsCompleted = true; break }
                            }
                        } finally {
                            ttsIsActive = false
                            if (paused) {
                                if (ttsCompleted) {
                                    Log.d("PicoClaw", "[tts] stream complete ($chunkIndex chunks)")
                                } else {
                                    Log.d("PicoClaw", "[tts] stream interrupted after $chunkIndex chunks")
                                }
                                if (ttsCompleted) {
                                    val finalText = pendingAssistantFinalText
                                        ?: _messages.value.lastOrNull { msg ->
                                            msg.role == "assistant" &&
                                            (msg.serverMessageId == null || msg.serverMessageId in currentRoundMessageIds)
                                        }?.content
                                    if (!finalText.isNullOrBlank()) {
                                        finalizeAssistantTurnNow(finalText, "tts-final")
                                    }
                                }
                                runCatching { mic.resumeAfterTts() }
                            }
                            ttsJob = null
                        }
                    }
                }
            }

            is PcEvent.Errored -> {
                typingActive = false
                _errorText.value = event.message
                if (_state.value == PcState.Connected) {
                    _messages.update {
                        it + PcChatMessage(role = "system_error", content = event.message)
                    }
                }
                pendingAssistantFinalText = null
                pendingCompletionFallbackJob?.cancel()
                pendingCompletionFallbackJob = null
                finalizeAssistantTurnNow(event.message, "error")
            }

            is PcEvent.Disconnected -> {
                typingActive = false
                ttsIsActive = false
                mic.onGatewayConnectionChanged(false)
                ttsJob?.cancel()
                ttsJob = null
                sawProxyTtsChunkThisTurn = false
                pendingAssistantFinalText = null
                pendingCompletionFallbackJob?.cancel()
                pendingCompletionFallbackJob = null
                voicePromptJob?.cancel()
                voicePromptJob = null
                kwsTtsPlayer.stopVoicePrompt()
                viewModelScope.launch(Dispatchers.IO) { proxyTtsAudioPlayer.stop() }
                webSocket = null
                _messages.update { msgs -> msgs.filter { it.role != "typing" } }
                if (wasUserDisconnect) {
                    val pr = pendingReconnect
                    _errorText.value = null
                    wasUserDisconnect = false
                    pendingReconnect = false
                    // pendingReconnect means stopStreaming() already called connect()
                    if (!pr) _state.value = PcState.Idle
                } else {
                    // Unexpected disconnect — auto-reconnect with backoff
                    scheduleReconnect()
                }
            }
        }
    }

    private fun completeAssistantTurn(finalText: String, source: String) {
        if (mode.value == PcMode.Proxy) {
            pendingAssistantFinalText = finalText
            if (sawProxyTtsChunkThisTurn || ttsIsActive) {
                Log.d("PicoClaw", "[voice] completion deferred until final TTS chunk source=$source")
                return
            }
            // Fallback: if this turn does not actually produce proxy TTS, complete on text.
            // Important: schedule only once. Repeated message.create/update frames can arrive
            // every few hundred ms; if we keep cancel+reschedule, completion is starved and
            // promptedThinking/promptedToolCalls remain stuck true for too long.
            if (pendingCompletionFallbackJob?.isActive != true) {
                pendingCompletionFallbackJob = viewModelScope.launch(Dispatchers.IO) {
                    delay(1200L)
                    if (!sawProxyTtsChunkThisTurn) {
                        val text = pendingAssistantFinalText
                        pendingAssistantFinalText = null
                        if (!text.isNullOrBlank()) finalizeAssistantTurnNow(text, "fallback-text:$source")
                    }
                }
            }
            return
        }
        finalizeAssistantTurnNow(finalText, "text:$source")
    }

    private fun finalizeAssistantTurnNow(finalText: String, reason: String) {
        mic.onExternalAssistantComplete(finalText)
        sawProxyTtsChunkThisTurn = false
        pendingAssistantFinalText = null
        pendingCompletionFallbackJob?.cancel()
        pendingCompletionFallbackJob = null
        currentRoundMessageIds.clear()
        Log.d("PicoClaw", "[voice] assistant turn finalized reason=$reason")
    }

    // ── Send ──────────────────────────────────────────────────────────────────
    fun sendMessage(text: String, attachments: List<PcImageAttachment> = emptyList(), isVoiceInitiated: Boolean = false) {
        val ws = webSocket ?: return
        val content = text.trim()
        if (content.isEmpty() && attachments.isEmpty()) return
        // Start a fresh prompt round even if typing.start is delayed or missing.
        voicePromptJob?.cancel()
        voicePromptJob = null
        kwsTtsPlayer.stopVoicePrompt()
        sawProxyTtsChunkThisTurn = false
        lastTurnWasVoice = isVoiceInitiated
        pendingAssistantFinalText = null
        pendingCompletionFallbackJob?.cancel()
        pendingCompletionFallbackJob = null
        ttsIsActive = false
        Log.d("PicoClaw", "[voice] sendMessage — voice state reset (voice=$isVoiceInitiated)")
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

    // ── Auto-reconnect ────────────────────────────────────────────────────────
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempt++
        if (reconnectAttempt > maxReconnectAttempts) {
            reconnectAttempt = 0
            _state.value = PcState.Error
            _errorText.value = "Connection lost — could not reconnect after $maxReconnectAttempts attempts"
            return
        }
        // Exponential backoff capped at 30 s: 2 s, 4 s, 8 s, 16 s, 30 s …
        val delayMs = minOf(reconnectBaseDelayMs * (1L shl (reconnectAttempt - 1)), 30_000L)
        _state.value = PcState.Reconnecting
        _errorText.value = "Connection lost — retrying in ${delayMs / 1000}s (attempt $reconnectAttempt/$maxReconnectAttempts)…"
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!isActive) return@launch
            try {
                val currentMode = mode.value
                if (currentMode == PcMode.Proxy) {
                    _state.value = PcState.Connecting
                    openProxyWebSocket()
                    return@launch
                }
                val wsUrl: String
                val effectiveToken: String
                if (currentMode == PcMode.WebBackend) {
                    _state.value = PcState.FetchingToken
                    val resp = session.fetchToken(host.value, webPort.value)
                    if (!resp.enabled) {
                        _state.value = PcState.Error
                        _errorText.value = "PicoClaw channel is not enabled on this gateway"
                        reconnectAttempt = 0
                        return@launch
                    }
                    effectiveToken = resp.token
                    wsUrl = normalizeWsUrl(resp.wsUrl, host.value)
                    cachedWsUrl = wsUrl
                    cachedToken = effectiveToken
                    _token.value = effectiveToken
                    prefs.edit().putString("token", effectiveToken).apply()
                } else {
                    effectiveToken = cachedToken
                        ?: tokenInput.value.trim().ifEmpty { _token.value ?: "" }
                    if (effectiveToken.isBlank()) {
                        _state.value = PcState.Error
                        _errorText.value = "Token is required in Direct mode"
                        reconnectAttempt = 0
                        return@launch
                    }
                    wsUrl = cachedWsUrl ?: "ws://${host.value}:${gatewayPort.value}/pico/ws"
                }
                _state.value = PcState.Connecting
                openWebSocket(wsUrl = wsUrl, token = effectiveToken)
            } catch (e: Exception) {
                if (isActive) {
                    _errorText.value = "Reconnect attempt $reconnectAttempt failed: ${e.message}"
                    scheduleReconnect() // next backoff tier
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
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
        mic.setMicEnabled(false)
        reconnectJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        viewModelScope.launch(Dispatchers.IO) { proxyTtsAudioPlayer.release() }
        kwsTtsPlayer.release()
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
