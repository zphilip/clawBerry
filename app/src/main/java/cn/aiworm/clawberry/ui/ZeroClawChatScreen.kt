package clawberry.aiworm.cn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.res.painterResource
import clawberry.aiworm.cn.R
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import clawberry.aiworm.cn.zeroclaw.ZcChatMessage
import clawberry.aiworm.cn.zeroclaw.ZcImageAttachment
import clawberry.aiworm.cn.zeroclaw.ZcSessionInfo
import clawberry.aiworm.cn.zeroclaw.ZcSessionPickerState
import clawberry.aiworm.cn.zeroclaw.ZcState
import clawberry.aiworm.cn.zeroclaw.ZeroClawViewModel
import clawberry.aiworm.cn.ui.chat.PendingImageAttachment
import clawberry.aiworm.cn.ui.chat.loadSizedImageAttachment
import clawberry.aiworm.cn.ui.chat.ChatMarkdown
import clawberry.aiworm.cn.ui.chat.isLikelyHtml
import clawberry.aiworm.cn.ui.chat.rememberBase64ImageState
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import clawberry.aiworm.cn.asr.AsrClient
import clawberry.aiworm.cn.asr.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Sub-tab enum – mirrors OpenClaw's HomeTab pattern
// ---------------------------------------------------------------------------
private enum class ZcTab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Connect(labelRes = R.string.common_connect, icon = Icons.Default.CheckCircle),
    Chat(labelRes = R.string.common_chat, icon = Icons.Default.ChatBubble),
    Settings(labelRes = R.string.tab_settings, icon = Icons.Default.Settings),
}

// ---------------------------------------------------------------------------
// Root entry point – always shows its own sub-tab bar at the bottom
// ---------------------------------------------------------------------------
@Composable
fun ZeroClawChatScreen(viewModel: ZeroClawViewModel) {
    val state by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val errorText by viewModel.errorText.collectAsState()
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()
    val pairCode by viewModel.pairCode.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()
    val sessionPicker by viewModel.sessionPicker.collectAsState()
    val useProxy by viewModel.useProxy.collectAsState()
    val proxyPort by viewModel.proxyPort.collectAsState()

    var activeTab by rememberSaveable { mutableStateOf(ZcTab.Connect) }

    // Auto-switch to Chat as soon as WS is live; back to Connect on disconnect/error
    LaunchedEffect(state) {
        when (state) {
            ZcState.Connected -> activeTab = ZcTab.Chat
            ZcState.Idle, ZcState.Error ->
                if (activeTab == ZcTab.Chat) activeTab = ZcTab.Connect
            ZcState.Reconnecting -> Unit  // stay on current tab while reconnecting
            else -> Unit
        }
    }

    val isConnected = state == ZcState.Connected

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ── Top status bar ────────────────────────────────────────────────────
        ZcStatusBar(state = state, host = host, port = port)

        // ── Tab content + floating rail ───────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                ZcTab.Connect ->
                    ZcConnectTab(
                        state = state,
                        host = host,
                        port = port,
                        token = token,
                        tokenInput = tokenInput,
                        pairCode = pairCode,
                        errorText = errorText,
                        useProxy = useProxy,
                        proxyPort = proxyPort,
                        onUseProxyChange = { viewModel.setUseProxy(it) },
                        onProxyPortChange = { viewModel.setProxyPort(it) },
                        viewModel = viewModel,
                    )

                ZcTab.Chat ->
                    ZcChatTab(
                        isConnected = isConnected,
                        messages = messages,
                        asrUrl = LocalContext.current
                            .getSharedPreferences("openclaw.node", android.content.Context.MODE_PRIVATE)
                            .getString("asr.url", "wss://asr.aiworm.cn:443") ?: "wss://asr.aiworm.cn:443",
                        onSend = { text, atts ->
                            viewModel.sendMessage(
                                text,
                                atts.map { ZcImageAttachment(mimeType = it.mimeType, fileName = it.fileName, base64 = it.base64) },
                            )
                        },
                        onClearChat = { viewModel.clearMessages() },
                        onRefresh = { viewModel.regenerateLastMessage() },
                        onStop = { viewModel.stopStreaming() },
                        onGoConnect = { activeTab = ZcTab.Connect },
                    )

                ZcTab.Settings ->
                    ZcSettingsTab(
                        host = host,
                        port = port,
                        onHostChange = { viewModel.setHost(it) },
                        onPortChange = { v -> v.toIntOrNull()?.let { viewModel.setPort(it) } },
                        token = token,
                        onClearToken = { viewModel.clearAndReset() },
                    )
            }
            val zcTabConnect = stringResource(R.string.common_connect)
            val zcTabChat = stringResource(R.string.common_chat)
            val zcTabSettings = stringResource(R.string.tab_settings)
            VerticalTabRail(
                tabs = ZcTab.entries,
                activeTab = activeTab,
                onSelect = { activeTab = it },
                icon = { tab -> tab.icon },
                label = { tab ->
                    when (tab) {
                        ZcTab.Connect -> zcTabConnect
                        ZcTab.Chat -> zcTabChat
                        ZcTab.Settings -> zcTabSettings
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

    }

    // ── Session picker + restoring dialogs ────────────────────────────────────
    when (val picker = sessionPicker) {
        is ZcSessionPickerState.Choosing ->
            ZcSessionPickerDialog(
                sessions = picker.sessions,
                onPickNew = { viewModel.pickNewSession() },
                onPickRestore = { viewModel.pickRestoreSession(it) },
            )
        ZcSessionPickerState.Restoring -> ZcRestoringDialog()
        ZcSessionPickerState.Hidden -> Unit
    }
}

// ---------------------------------------------------------------------------
// Top status bar – mirrors OpenClaw's gateway status chip
// ---------------------------------------------------------------------------
@Composable
private fun ZcStatusBar(state: ZcState, host: String, port: Int, useProxy: Boolean = false, proxyPort: Int = 18780) {
    val dotColor: Color
    val textColor: Color
    val bgColor: Color
    val borderColor: Color
    val label: String
    val connectedLabel = if (useProxy)
        stringResource(R.string.zc_status_connected, host, proxyPort) + " ⁀proxy"
    else
        stringResource(R.string.zc_status_connected, host, port)
    val connectingLabel = stringResource(R.string.zc_status_connecting)
    val pairingRequiredLabel = stringResource(R.string.zc_status_pairing_required)
    val reconnectingLabel = stringResource(R.string.zc_status_reconnecting)
    val errorLabel = stringResource(R.string.common_error)
    val offlineLabel = stringResource(R.string.common_offline)
    when (state) {
        ZcState.Connected -> {
            label = connectedLabel
            dotColor = mobileSuccess
            textColor = mobileSuccess
            bgColor = mobileSuccessSoft
            borderColor = LocalMobileColors.current.chipBorderConnected
        }
        ZcState.Connecting, ZcState.HealthChecking, ZcState.Pairing -> {
            label = connectingLabel
            dotColor = mobileAccent
            textColor = mobileAccent
            bgColor = mobileAccentSoft
            borderColor = LocalMobileColors.current.chipBorderConnecting
        }
        ZcState.NeedsPairing -> {
            label = pairingRequiredLabel
            dotColor = mobileWarning
            textColor = mobileWarning
            bgColor = mobileWarningSoft
            borderColor = LocalMobileColors.current.chipBorderWarning
        }
        ZcState.Reconnecting -> {
            label = reconnectingLabel
            dotColor = Color(0xFFFFA726)
            textColor = Color(0xFFFFA726)
            bgColor = Color(0xFFFFA726).copy(alpha = 0.12f)
            borderColor = Color(0xFFFFA726).copy(alpha = 0.4f)
        }
        ZcState.Error -> {
            label = errorLabel
            dotColor = mobileDanger
            textColor = mobileDanger
            bgColor = mobileDangerSoft
            borderColor = LocalMobileColors.current.chipBorderError
        }
        ZcState.Idle -> {
            label = offlineLabel
            dotColor = mobileTextTertiary
            textColor = mobileTextSecondary
            bgColor = mobileSurface
            borderColor = mobileBorder
        }
    }

    val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Surface(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(safeInsets),
        color = mobileAccentSoft,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_zeroclaw),
                    contentDescription = "ZeroClaw",
                    modifier = Modifier.size(28.dp),
                )
                Text("ZeroClaw", style = mobileTitle2, color = mobileText)
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = bgColor,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.padding(top = 1.dp),
                        color = dotColor,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Box(modifier = Modifier.padding(4.dp))
                    }
                    Text(
                        text = label,
                        style = mobileCaption1,
                        color = textColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connect tab – setup form OR pairing form OR connected status + disconnect
// ---------------------------------------------------------------------------
@Composable
private fun ZcConnectTab(
    state: ZcState,
    host: String,
    port: Int,
    token: String?,
    tokenInput: String,
    pairCode: String,
    errorText: String?,
    useProxy: Boolean,
    proxyPort: Int,
    onUseProxyChange: (Boolean) -> Unit,
    onProxyPortChange: (Int) -> Unit,
    viewModel: ZeroClawViewModel,
) {
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Section header
        val zcActiveReady = stringResource(R.string.zc_gateway_active_ready)
        val zcConnectDirectly = stringResource(R.string.zc_connect_directly)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.zc_gateway_connection), style = mobileTitle1, color = mobileText)
            Text(
                if (state == ZcState.Connected) zcActiveReady else zcConnectDirectly,
                style = mobileCallout,
                color = mobileTextSecondary,
            )
        }

        // ── Status info card (always visible) ─────────────────────────────────
        ZcInfoCard(state = state, host = host, port = port, token = token, onClearToken = { viewModel.clearAndReset() })

        // ── Error card ─────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = !errorText.isNullOrBlank() && state == ZcState.Error,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            errorText?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = mobileDangerSoft,
                    border = BorderStroke(1.dp, mobileDanger.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.zc_connection_error),
                            style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileDanger,
                        )
                        Text(
                            it,
                            style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                            color = mobileText,
                        )
                    }
                }
            }
        }

        // ── Pairing card ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = state == ZcState.NeedsPairing,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ZcPairCard(
                pairCode = pairCode,
                onPairCodeChange = { viewModel.pairCode.value = it },
                errorText = if (state == ZcState.NeedsPairing) errorText else null,
                onPair = { viewModel.pair() },
            )
        }

        // ── Main action area ────────────────────────────────────────────────────
        when (state) {
            ZcState.Connected -> {
                // Disconnect button – mirrors ConnectTabScreen exactly
                Button(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mobileCardSurface,
                        contentColor = mobileDanger,
                    ),
                    border = BorderStroke(1.dp, mobileDanger.copy(alpha = 0.4f)),
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.common_disconnect_action),
                        style = mobileHeadline.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            ZcState.HealthChecking, ZcState.Pairing, ZcState.Connecting, ZcState.Reconnecting -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = if (state == ZcState.Reconnecting) Color(0xFFFFA726) else mobileAccent,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    val zcCheckingGateway = stringResource(R.string.zc_checking_gateway)
                    val zcPairing = stringResource(R.string.zc_pairing)
                    val zcReconnecting = stringResource(R.string.zc_reconnecting_attempt, reconnectAttempt)
                    val zcConnecting = stringResource(R.string.zc_status_connecting)
                    Text(
                        text = when (state) {
                            ZcState.HealthChecking -> zcCheckingGateway
                            ZcState.Pairing -> zcPairing
                            ZcState.Reconnecting -> zcReconnecting
                            else -> zcConnecting
                        },
                        style = mobileCallout,
                        color = if (state == ZcState.Reconnecting) Color(0xFFFFA726) else mobileTextSecondary,
                    )
                }
            }

            ZcState.NeedsPairing -> {
                // Pair & Connect button lives inside ZcPairCard above
            }

            ZcState.Idle, ZcState.Error -> {
                FindClawGatewayCard(
                    serviceTypeFilter = clawberry.aiworm.cn.gateway.ClawServiceType.ATTR_GATEWAY,
                    onGatewaySelected = { selectedHost, selectedPort, _ ->
                        viewModel.setHost(selectedHost)
                        viewModel.setPort(selectedPort)
                    },
                )
                ZcSetupForm(
                    host = host,
                    port = port,
                    token = token,
                    tokenInput = tokenInput,
                    onHostChange = { viewModel.setHost(it) },
                    onPortChange = { v -> v.toIntOrNull()?.let { viewModel.setPort(it) } },
                    onTokenInputChange = { viewModel.tokenInput.value = it },
                    onConnect = { viewModel.connect() },
                    useProxy = useProxy,
                    proxyPort = proxyPort,
                    onUseProxyChange = onUseProxyChange,
                    onProxyPortChange = onProxyPortChange,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Info card – endpoint + status rows (always shown on Connect tab)
// ---------------------------------------------------------------------------
@Composable
private fun ZcInfoCard(
    state: ZcState,
    host: String,
    port: Int,
    token: String?,
    onClearToken: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Column {
            // Endpoint row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = RoundedCornerShape(10.dp), color = mobileAccentSoft) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(18.dp),
                        tint = mobileAccent,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.common_endpoint),
                        style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                        color = mobileTextSecondary,
                    )
                    Text(
                        "$host:$port",
                        style = mobileBody.copy(fontFamily = FontFamily.Monospace),
                        color = mobileText,
                    )
                }
            }

            HorizontalDivider(color = mobileBorder)

            // Status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val iconBg = when (state) {
                    ZcState.Connected -> mobileSuccessSoft
                    ZcState.Error -> mobileDangerSoft
                    ZcState.NeedsPairing -> mobileWarningSoft
                    else -> mobileSurface
                }
                Surface(shape = RoundedCornerShape(10.dp), color = iconBg) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (state) {
                            ZcState.HealthChecking, ZcState.Pairing, ZcState.Connecting ->
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(),
                                    color = mobileAccent,
                                    strokeWidth = 2.dp,
                                )
                            else ->
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    tint = when (state) {
                                        ZcState.Connected -> mobileSuccess
                                        ZcState.Error -> mobileDanger
                                        ZcState.NeedsPairing -> mobileWarning
                                        else -> mobileTextTertiary
                                    },
                                )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.common_status),
                        style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                        color = mobileTextSecondary,
                    )
                    val zcConnectedLabel = stringResource(R.string.zc_state_connected)
                    val zcCheckGwLabel = stringResource(R.string.zc_checking_gateway)
                    val zcNeedsPairLabel = stringResource(R.string.zc_status_pairing_required)
                    val zcPairingLabel = stringResource(R.string.zc_pairing)
                    val zcConnectingLabel = stringResource(R.string.zc_status_connecting)
                    val zcReconnectingLabel = stringResource(R.string.zc_status_reconnecting)
                    val zcErrorLabel = stringResource(R.string.common_error)
                    val zcOfflineLabel = stringResource(R.string.common_offline)
                    Text(
                        text = when (state) {
                            ZcState.Connected -> zcConnectedLabel
                            ZcState.HealthChecking -> zcCheckGwLabel
                            ZcState.NeedsPairing -> zcNeedsPairLabel
                            ZcState.Pairing -> zcPairingLabel
                            ZcState.Connecting -> zcConnectingLabel
                            ZcState.Reconnecting -> zcReconnectingLabel
                            ZcState.Error -> zcErrorLabel
                            ZcState.Idle -> zcOfflineLabel
                        },
                        style = mobileBody,
                        color = when (state) {
                            ZcState.Connected -> mobileSuccess
                            ZcState.Error -> mobileDanger
                            ZcState.NeedsPairing -> mobileWarning
                            else -> mobileText
                        },
                    )
                }
            }

            // Token row (only when a token is saved)
            if (!token.isNullOrBlank()) {
                HorizontalDivider(color = mobileBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.zc_token),
                            style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileTextSecondary,
                        )
                        Text(
                            "${token.take(14)}…",
                            style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                            color = mobileSuccess,
                        )
                    }
                    TextButton(onClick = onClearToken) {
                        Text(
                            stringResource(R.string.zc_clear),
                            style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileDanger,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pairing card – inline within Connect tab
// ---------------------------------------------------------------------------
@Composable
private fun ZcPairCard(
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    errorText: String?,
    onPair: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = mobileWarningSoft,
        border = BorderStroke(1.dp, mobileWarning.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.zc_pairing_required), style = mobileHeadline, color = mobileWarning)
            Text(
                stringResource(R.string.zc_get_paircode_hint),
                style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                color = mobileTextSecondary,
            )

            AnimatedVisibility(
                visible = !errorText.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                errorText?.let {
                    Text(it, style = mobileCaption1, color = mobileDanger)
                }
            }

            ZcTextField(
                value = pairCode,
                onValueChange = onPairCodeChange,
                label = stringResource(R.string.zc_pair_code),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onImeAction = { if (pairCode.isNotBlank()) onPair() },
                accentColor = mobileWarning,
            )

            Button(
                onClick = onPair,
                enabled = pairCode.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = mobileAccent,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    stringResource(R.string.zc_pair_and_connect),
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Setup form – shown on Connect tab when Idle or Error
// ---------------------------------------------------------------------------
@Composable
private fun ZcSetupForm(
    host: String,
    port: Int,
    token: String?,
    tokenInput: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    useProxy: Boolean = false,
    proxyPort: Int = 18780,
    onUseProxyChange: (Boolean) -> Unit = {},
    onProxyPortChange: (Int) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Via Proxy toggle card ──────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (useProxy) mobileAccentSoft else mobileCardSurface,
            border = BorderStroke(
                1.dp,
                if (useProxy) LocalMobileColors.current.chipBorderConnecting else mobileBorder,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.zc_via_proxy),
                        style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                        color = if (useProxy) mobileAccent else mobileText,
                    )
                    Text(
                        stringResource(R.string.zc_via_proxy_subtitle),
                        style = mobileCaption1,
                        color = if (useProxy) mobileAccent.copy(alpha = 0.7f) else mobileTextTertiary,
                    )
                }
                Switch(
                    checked = useProxy,
                    onCheckedChange = onUseProxyChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = mobileAccent,
                    ),
                )
            }
        }

        // ── Connection fields (content changes based on proxy toggle) ─────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (useProxy) stringResource(R.string.zc_via_proxy) else stringResource(R.string.zc_gateway),
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileTextSecondary,
                )
                if (useProxy) {
                    // Proxy mode: host + proxy port
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZcTextField(
                            value = host,
                            onValueChange = onHostChange,
                            label = stringResource(R.string.common_host),
                            modifier = Modifier.weight(3f),
                            keyboardType = KeyboardType.Uri,
                        )
                        ZcTextField(
                            value = proxyPort.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { onProxyPortChange(it) } },
                            label = stringResource(R.string.zc_proxy_port),
                            modifier = Modifier.weight(1.5f),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                } else {
                    // Direct mode: host + gateway port + optional token
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZcTextField(
                            value = host,
                            onValueChange = onHostChange,
                            label = stringResource(R.string.common_host),
                            modifier = Modifier.weight(3f),
                            keyboardType = KeyboardType.Uri,
                        )
                        ZcTextField(
                            value = port.toString(),
                            onValueChange = onPortChange,
                            label = stringResource(R.string.common_port),
                            modifier = Modifier.weight(1.5f),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    if (token.isNullOrBlank()) {
                        ZcTextField(
                            value = tokenInput,
                            onValueChange = onTokenInputChange,
                            label = stringResource(R.string.zc_bearer_token_optional),
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        )
                    }
                }
            }
        }

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = mobileAccent,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (useProxy) stringResource(R.string.zc_connect_via_proxy) else stringResource(R.string.zc_connect_gateway),
                style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Chat tab – message list + composer, or "connect first" placeholder
// ---------------------------------------------------------------------------
@Composable
private fun ZcChatTab(
    isConnected: Boolean,
    messages: List<ZcChatMessage>,
    asrUrl: String,
    onSend: (String, List<PendingImageAttachment>) -> Unit,
    onClearChat: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onGoConnect: () -> Unit,
) {
    if (!isConnected) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = mobileTextTertiary,
                    modifier = Modifier.size(40.dp),
                )
                Text(stringResource(R.string.zc_not_connected), style = mobileHeadline, color = mobileTextSecondary)
                Text(
                    stringResource(R.string.zc_setup_gateway_first),
                    style = mobileCallout,
                    color = mobileTextTertiary,
                )
                Button(
                    onClick = onGoConnect,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mobileAccent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        stringResource(R.string.zc_go_to_connect),
                        style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        return
    }

    val attachments = remember { mutableStateListOf<PendingImageAttachment>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val listState = rememberLazyListState()

    val pickImages =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val next =
                    uris.take(8).mapNotNull { uri ->
                        try {
                            loadSizedImageAttachment(resolver, uri)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                withContext(Dispatchers.Main) { attachments.addAll(next) }
            }
        }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = messages, key = { it.id }) { msg -> ZcMessageBubble(msg) }
        }

        HorizontalDivider(color = mobileBorder, thickness = 1.dp)

        ZcComposer(
            attachments = attachments,
            asrUrl = asrUrl,
            onPickImages = { pickImages.launch("image/*") },
            onRemoveAttachment = { id -> attachments.removeAll { it.id == id } },
            onClearChat = onClearChat,
            onRefresh = onRefresh,
            onStop = onStop,
            onSend = { text ->
                onSend(text, attachments.toList())
                attachments.clear()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Chat composer – text field + Attach / Clear / Send action buttons
// ---------------------------------------------------------------------------
@Composable
private fun ZcComposer(
    attachments: List<PendingImageAttachment>,
    asrUrl: String,
    onPickImages: () -> Unit,
    onRemoveAttachment: (id: String) -> Unit,
    onClearChat: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var voiceState by remember { mutableStateOf(clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle) }
    var capturedPcm by remember { mutableStateOf<ByteArray?>(null) }
    var asrMode by rememberSaveable { mutableStateOf("2pass") }
    var transcribeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val voiceRecorder = remember { VoiceRecorder() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { voiceRecorder.release() } }
    val canSend = input.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Attachment chips strip
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { att ->
                    ZcAttachmentChip(
                        fileName = att.fileName,
                        onRemove = { onRemoveAttachment(att.id) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(stringResource(R.string.zc_message_placeholder), style = mobileBody, color = mobileTextTertiary)
            },
            textStyle = mobileBody.copy(color = mobileText),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = mobileAccent,
                unfocusedBorderColor = mobileBorder,
                cursorColor = mobileAccent,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = { if (canSend) { onSend(input); input = "" } },
            ),
            maxLines = 6,
        )

        capturedPcm?.let { pcm ->
            clawberry.aiworm.cn.ui.chat.VoiceClipBar(
                pcm = pcm,
                isTranscribing = voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZcActionButton(
                icon = Icons.Default.AttachFile,
                label = stringResource(R.string.common_attach),
                onClick = onPickImages,
            )
            ZcActionButton(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.zc_regenerate),
                onClick = onRefresh,
            )
            ZcActionButton(
                icon = Icons.Default.Stop,
                label = stringResource(R.string.zc_stop),
                onClick = onStop,
            )
            ZcActionButton(
                icon = Icons.Default.Delete,
                label = stringResource(R.string.zc_clear_chat),
                onClick = onClearChat,
            )
            // ── Mic / Voice input button ──────────────────────────────────
            ZcActionButton(
                icon = when (voiceState) {
                    clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording -> Icons.Default.MicOff
                    else -> Icons.Default.Mic
                },
                label = when (voiceState) {
                    clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle -> stringResource(R.string.asr_mic_start)
                    clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording -> stringResource(R.string.asr_recording)
                    clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing -> stringResource(R.string.asr_transcribing)
                    clawberry.aiworm.cn.ui.chat.AsrVoiceState.Failed -> stringResource(R.string.asr_failed)
                },
                containerColor = if (voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording)
                    Color(0xFFE53935) else mobileCardSurface,
                iconTint = if (voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording)
                    Color.White else mobileTextSecondary,
                onClick = {
                    when (voiceState) {
                        clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle -> {
                            capturedPcm = null
                            voiceRecorder.start()
                            voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording
                        }
                        clawberry.aiworm.cn.ui.chat.AsrVoiceState.Failed -> {
                            val pcmToRetry = capturedPcm
                            if (pcmToRetry != null) {
                                voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing
                                transcribeJob = scope.launch(Dispatchers.IO) {
                                    val text = AsrClient.transcribe(asrUrl, pcmToRetry, asrMode)
                                    withContext(Dispatchers.Main) {
                                        if (!text.isNullOrBlank()) {
                                            input = text
                                            capturedPcm = null
                                            voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle
                                            android.widget.Toast.makeText(
                                                context, context.getString(R.string.asr_success),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Failed
                                            android.widget.Toast.makeText(
                                                context, context.getString(R.string.asr_failed_message),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            } else {
                                voiceRecorder.start()
                                voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording
                            }
                        }
                        clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording -> {
                            voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing
                            transcribeJob = scope.launch(Dispatchers.IO) {
                                val pcm = voiceRecorder.stop()
                                if (VoiceRecorder.isSilent(pcm)) {
                                    withContext(Dispatchers.Main) {
                                        voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle
                                        android.widget.Toast.makeText(
                                            context, context.getString(R.string.asr_no_voice),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    return@launch
                                }
                                withContext(Dispatchers.Main) { capturedPcm = pcm }
                                val text = AsrClient.transcribe(asrUrl, pcm, asrMode)
                                withContext(Dispatchers.Main) {
                                    if (!text.isNullOrBlank()) {
                                        input = text
                                        capturedPcm = null
                                        voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle
                                        android.widget.Toast.makeText(
                                            context, context.getString(R.string.asr_success),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Failed
                                        android.widget.Toast.makeText(
                                            context, context.getString(R.string.asr_failed_message),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                        clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing -> Unit
                    }
                },
            )

            // ── ASR cancel button (visible while recording or transcribing) ──
            if (voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording ||
                voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Transcribing) {
                ZcActionButton(
                    icon = Icons.Default.Close,
                    label = stringResource(R.string.asr_cancel),
                    onClick = {
                        if (voiceState == clawberry.aiworm.cn.ui.chat.AsrVoiceState.Recording) {
                            voiceRecorder.release()  // stops mic, discards PCM — nothing sent to ASR
                        } else {
                            transcribeJob?.cancel()  // abort in-flight HTTP request
                        }
                        transcribeJob = null
                        capturedPcm = null
                        voiceState = clawberry.aiworm.cn.ui.chat.AsrVoiceState.Idle
                    },
                )
            }

            // ── ASR mode selector (tap to cycle: 2pass → offline → online) ──
            Surface(
                onClick = {
                    asrMode = when (asrMode) { "2pass" -> "offline"; "offline" -> "online"; else -> "2pass" }
                },
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = mobileCardSurface,
                border = BorderStroke(1.dp, mobileBorder),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = asrMode,
                        style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                        color = mobileTextSecondary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSend(input); input = "" },
                enabled = canSend,
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = mobileAccent,
                    contentColor = Color.White,
                    disabledContainerColor = mobileBorder,
                    disabledContentColor = mobileTextTertiary,
                ),
                border = BorderStroke(
                    1.dp,
                    if (canSend) mobileAccentBorderStrong else mobileBorder,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.common_send),
                    style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun ZcActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color = mobileCardSurface,
    iconTint: Color = mobileTextSecondary,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = iconTint,
        ),
        border = BorderStroke(1.dp, mobileBorder),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ZcAttachmentChip(fileName: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = mobileAccentSoft,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = mobileAccent,
            )
            Text(
                text = fileName,
                style = mobileCaption1,
                color = mobileText,
                maxLines = 1,
            )
            Surface(
                onClick = onRemove,
                shape = RoundedCornerShape(999.dp),
                color = mobileCardSurface,
                border = BorderStroke(1.dp, mobileBorder),
            ) {
                Text(
                    text = "×",
                    style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
                    color = mobileTextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings tab – edit host/port, clear saved token
// ---------------------------------------------------------------------------
@Composable
private fun ZcSettingsTab(
    host: String,
    port: Int,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    token: String?,
    onClearToken: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = mobileTitle1, color = mobileText)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.zc_gateway_address),
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileTextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZcTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = stringResource(R.string.common_host),
                        modifier = Modifier.weight(3f),
                        keyboardType = KeyboardType.Uri,
                    )
                    ZcTextField(
                        value = port.toString(),
                        onValueChange = onPortChange,
                        label = stringResource(R.string.common_port),
                        modifier = Modifier.weight(1.5f),
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!token.isNullOrBlank()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.zc_saved_token),
                            style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileTextSecondary,
                        )
                        Text(
                            "${token.take(14)}…",
                            style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                            color = mobileSuccess,
                        )
                    }
                    TextButton(onClick = onClearToken) {
                        Text(
                            stringResource(R.string.zc_clear_and_reset),
                            style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileDanger,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.zc_no_token_saved),
                        style = mobileCallout,
                        color = mobileTextTertiary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: extract renderable HTML embedded inside a tool_call's JSON args.
// Handles content like: canvas({"action":"render","content":"<!DOCTYPE html>..."})
// JSONObject.getString() properly unescapes \n, \", etc.
// ---------------------------------------------------------------------------
private fun extractHtmlFromToolCallArgs(content: String): String? {
    val braceStart = content.indexOf('{')
    val braceEnd = content.lastIndexOf('}')
    if (braceStart < 0 || braceEnd <= braceStart) return null
    return try {
        val json = JSONObject(content.substring(braceStart, braceEnd + 1))
        json.keys().asSequence()
            .mapNotNull { key ->
                runCatching { json.getString(key) }.getOrNull()
                    ?.takeIf { isLikelyHtml(it) }
            }
            .firstOrNull()
    } catch (_: Exception) { null }
}

// ---------------------------------------------------------------------------
// Message bubble – user / assistant / tool_call / tool_result / system_error
// ---------------------------------------------------------------------------
@Composable
private fun ZcMessageBubble(msg: ZcChatMessage) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool_call" || msg.role == "tool_result"
    val isError = msg.role == "system_error"
    // For tool_call: check once if args contain renderable HTML
    val toolCallHtml = remember(msg.content) {
        if (msg.role == "tool_call") extractHtmlFromToolCallArgs(msg.content) else null
    }
    var fullscreenImage by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val onImageClick: (String, String?) -> Unit = { b64, mime -> fullscreenImage = b64 to mime }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        when {
            isUser ->
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp, topEnd = 6.dp,
                        bottomStart = 18.dp, bottomEnd = 18.dp,
                    ),
                    color = mobileAccent,
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Image thumbnails
                        for (att in msg.attachments) {
                            val imgState = rememberBase64ImageState(att.base64)
                            val img = imgState.image
                            if (img != null) {
                                Image(
                                    bitmap = img,
                                    contentDescription = att.fileName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White.copy(alpha = 0.8f),
                                    )
                                    Text(
                                        att.fileName,
                                        style = mobileCaption1,
                                        color = Color.White.copy(alpha = 0.8f),
                                    )
                                }
                            }
                        }
                        // Text content (omit if empty – e.g. image-only message)
                        if (msg.content.isNotBlank()) {
                            Text(
                                msg.content,
                                style = mobileBody,
                                color = Color.White,
                            )
                        }
                    }
                }

            isError ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = mobileDangerSoft,
                    border = BorderStroke(1.dp, mobileDanger.copy(alpha = 0.3f)),
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Text(
                        msg.content,
                        style = mobileCaption1,
                        color = mobileDanger,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

            isTool ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = mobileSurfaceStrong,
                    border = BorderStroke(1.dp, mobileBorder),
                    modifier = if (msg.role == "tool_result" || toolCallHtml != null)
                        Modifier.widthIn(max = 320.dp)
                    else
                        Modifier.widthIn(max = 280.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = if (msg.role == "tool_call") "\u2699 tool call" else "\u2713 tool result",
                            style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileTextTertiary,
                        )
                        Spacer(Modifier.height(2.dp))
                        if (msg.role == "tool_result") {
                            // Tool results may contain markdown text or data: images
                            ChatMarkdown(
                                text = msg.content,
                                textColor = mobileTextSecondary,
                                onImageClick = onImageClick,
                            )
                        } else if (toolCallHtml != null) {
                            // tool_call args contained an HTML payload — render it
                            ChatMarkdown(
                                text = toolCallHtml,
                                textColor = mobileTextSecondary,
                                onImageClick = onImageClick,
                            )
                        } else {
                            Text(
                                msg.content,
                                style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                                color = mobileTextSecondary,
                            )
                        }
                    }
                }

            else -> {
                val pulseAlpha =
                    if (msg.isStreaming) {
                        val t = rememberInfiniteTransition(label = "pulse")
                        t.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.5f,
                            animationSpec = infiniteRepeatable(
                                tween(700, easing = LinearEasing),
                                RepeatMode.Reverse,
                            ),
                            label = "a",
                        ).value
                    } else {
                        1f
                    }

                Surface(
                    shape = RoundedCornerShape(
                        topStart = 6.dp, topEnd = 18.dp,
                        bottomStart = 18.dp, bottomEnd = 18.dp,
                    ),
                    color = mobileCardSurface,
                    border = BorderStroke(1.dp, mobileBorder),
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .alpha(pulseAlpha),
                ) {
                    if (msg.isStreaming && msg.content.isEmpty()) {
                        // Still waiting for first chunk — show ellipsis placeholder
                        Text(
                            text = "…",
                            style = mobileBody,
                            color = mobileText,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    } else {
                        // Full markdown renderer: handles bold/italic/code/tables/
                        // inline images (data:image/...;base64,...) etc.
                        ChatMarkdown(
                            text = msg.content.ifEmpty { "…" },
                            textColor = mobileText,
                            onImageClick = onImageClick,
                        )
                    }
                }
            }
        }
    }
    // Full-screen image viewer — shown when user taps any image in the bubble
    fullscreenImage?.let { (base64, mimeType) ->
        FullscreenImageDialog(base64 = base64, mimeType = mimeType, onDismiss = { fullscreenImage = null })
    }
}

// ---------------------------------------------------------------------------
// Reusable outlined text field
// ---------------------------------------------------------------------------
@Composable
private fun ZcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    accentColor: Color? = null,
) {
    val accent = accentColor ?: mobileAccent
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = mobileCaption1, color = mobileTextTertiary) },
        textStyle = mobileCallout.copy(color = mobileText),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = mobileBorder,
            cursorColor = accent,
            focusedLabelColor = accent,
            unfocusedLabelColor = mobileTextTertiary,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onNext = { onImeAction?.invoke() },
        ),
    )
}

// ---------------------------------------------------------------------------
// Full-screen image viewer – tap anywhere to close
// ---------------------------------------------------------------------------
@Composable
private fun FullscreenImageDialog(base64: String, mimeType: String?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable { onDismiss() },
        ) {
            val isSvg = mimeType?.contains("svg", ignoreCase = true) == true
            if (isSvg) {
                val htmlSrc = remember(base64) {
                    "<html><body style=\"margin:0;padding:0;background:#111\">" +
                        "<img src=\"data:image/svg+xml;base64,$base64\" style=\"width:100%;height:auto\"/>" +
                        "</body></html>"
                }
                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.javaScriptEnabled = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                            loadData(htmlSrc, "text/html", "utf-8")
                        }
                    },
                    update = { wv -> wv.loadData(htmlSrc, "text/html", "utf-8") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                )
            } else {
                val imageState = rememberBase64ImageState(base64)
                imageState.image?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                    )
                }
            }
            Text(
                stringResource(R.string.common_tap_to_close),
                style = mobileCaption1,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Session picker dialog – shown after health/pair, before WS opens
// ---------------------------------------------------------------------------
@Composable
private fun ZcSessionPickerDialog(
    sessions: List<ZcSessionInfo>,
    onPickNew: () -> Unit,
    onPickRestore: (ZcSessionInfo) -> Unit,
) {
    Dialog(onDismissRequest = onPickNew) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.zc_resume_session), style = mobileTitle2, color = mobileText)
                    Text(
                        stringResource(R.string.zc_resume_session_subtitle),
                        style = mobileCallout,
                        color = mobileTextSecondary,
                    )
                }

                // Session list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions) { info ->
                        Surface(
                            onClick = { onPickRestore(info) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = mobileSurface,
                            border = BorderStroke(1.dp, mobileBorder),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    stringResource(R.string.zc_session_message_count, info.messageCount),
                                    style = mobileCallout.copy(fontWeight = FontWeight.Medium),
                                    color = mobileText,
                                )
                                if (info.lastActive.isNotBlank()) {
                                    Text(
                                        info.lastActive.take(19).replace('T', ' '),
                                        style = mobileCaption1,
                                        color = mobileTextSecondary,
                                    )
                                }
                                if (info.preview.isNotBlank()) {
                                    Text(
                                        info.preview,
                                        style = mobileCaption1,
                                        color = mobileTextTertiary,
                                        maxLines = 1,
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.zc_session_id_prefix, info.id.take(12)),
                                        style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                                        color = mobileTextTertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = mobileBorder)

                // New session button
                Button(
                    onClick = onPickNew,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mobileAccent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        stringResource(R.string.zc_start_new_session),
                        style = mobileHeadline.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZcRestoringDialog() {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = mobileAccent,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.zc_restoring_session), style = mobileCallout, color = mobileText)
            }
        }
    }
}
