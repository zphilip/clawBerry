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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import clawberry.aiworm.cn.zeroclaw.ZcChatMessage
import clawberry.aiworm.cn.zeroclaw.ZcImageAttachment
import clawberry.aiworm.cn.zeroclaw.ZcState
import clawberry.aiworm.cn.zeroclaw.ZeroClawViewModel
import clawberry.aiworm.cn.ui.chat.PendingImageAttachment
import clawberry.aiworm.cn.ui.chat.loadSizedImageAttachment
import clawberry.aiworm.cn.ui.chat.rememberBase64ImageState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Sub-tab enum – mirrors OpenClaw's HomeTab pattern
// ---------------------------------------------------------------------------
private enum class ZcTab(val label: String, val icon: ImageVector) {
    Connect(label = "Connect", icon = Icons.Default.CheckCircle),
    Chat(label = "Chat", icon = Icons.Default.ChatBubble),
    Settings(label = "Settings", icon = Icons.Default.Settings),
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

    var activeTab by rememberSaveable { mutableStateOf(ZcTab.Connect) }

    // Auto-switch to Chat as soon as WS is live; back to Connect on disconnect/error
    LaunchedEffect(state) {
        when (state) {
            ZcState.Connected -> activeTab = ZcTab.Chat
            ZcState.Idle, ZcState.Error ->
                if (activeTab == ZcTab.Chat) activeTab = ZcTab.Connect
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
                        viewModel = viewModel,
                    )

                ZcTab.Chat ->
                    ZcChatTab(
                        isConnected = isConnected,
                        messages = messages,
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
            VerticalTabRail(
                tabs = ZcTab.entries,
                activeTab = activeTab,
                onSelect = { activeTab = it },
                icon = { tab -> tab.icon },
                label = { tab -> tab.label },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

    }
}

// ---------------------------------------------------------------------------
// Top status bar – mirrors OpenClaw's gateway status chip
// ---------------------------------------------------------------------------
@Composable
private fun ZcStatusBar(state: ZcState, host: String, port: Int) {
    val dotColor: Color
    val textColor: Color
    val bgColor: Color
    val borderColor: Color
    val label: String
    when (state) {
        ZcState.Connected -> {
            label = "Connected · $host:$port"
            dotColor = mobileSuccess
            textColor = mobileSuccess
            bgColor = mobileSuccessSoft
            borderColor = LocalMobileColors.current.chipBorderConnected
        }
        ZcState.Connecting, ZcState.HealthChecking, ZcState.Pairing -> {
            label = "Connecting…"
            dotColor = mobileAccent
            textColor = mobileAccent
            bgColor = mobileAccentSoft
            borderColor = LocalMobileColors.current.chipBorderConnecting
        }
        ZcState.NeedsPairing -> {
            label = "Pairing required"
            dotColor = mobileWarning
            textColor = mobileWarning
            bgColor = mobileWarningSoft
            borderColor = LocalMobileColors.current.chipBorderWarning
        }
        ZcState.Error -> {
            label = "Error"
            dotColor = mobileDanger
            textColor = mobileDanger
            bgColor = mobileDangerSoft
            borderColor = LocalMobileColors.current.chipBorderError
        }
        ZcState.Idle -> {
            label = "Offline"
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
    viewModel: ZeroClawViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Section header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gateway Connection", style = mobileTitle1, color = mobileText)
            Text(
                if (state == ZcState.Connected)
                    "Your ZeroClaw gateway is active and ready."
                else
                    "Connect directly to a ZeroClaw gateway.",
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
                            "Connection error",
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
                        "Disconnect",
                        style = mobileHeadline.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            ZcState.HealthChecking, ZcState.Pairing, ZcState.Connecting -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = mobileAccent,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = when (state) {
                            ZcState.HealthChecking -> "Checking gateway…"
                            ZcState.Pairing -> "Pairing…"
                            else -> "Connecting…"
                        },
                        style = mobileCallout,
                        color = mobileTextSecondary,
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
                        "Endpoint",
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
                        "Status",
                        style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                        color = mobileTextSecondary,
                    )
                    Text(
                        text = when (state) {
                            ZcState.Connected -> "Connected"
                            ZcState.HealthChecking -> "Checking gateway…"
                            ZcState.NeedsPairing -> "Pairing required"
                            ZcState.Pairing -> "Pairing…"
                            ZcState.Connecting -> "Connecting…"
                            ZcState.Error -> "Error"
                            ZcState.Idle -> "Offline"
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
                            "Token",
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
                            "Clear",
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
            Text("Pairing required", style = mobileHeadline, color = mobileWarning)
            Text(
                "Get your code:  zeroclaw gateway get-paircode",
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
                label = "Pair code",
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
                    "Pair & Connect",
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    "Gateway",
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileTextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZcTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = "Host",
                        modifier = Modifier.weight(3f),
                        keyboardType = KeyboardType.Uri,
                    )
                    ZcTextField(
                        value = port.toString(),
                        onValueChange = onPortChange,
                        label = "Port",
                        modifier = Modifier.weight(1.5f),
                        keyboardType = KeyboardType.Number,
                    )
                }
                if (token.isNullOrBlank()) {
                    ZcTextField(
                        value = tokenInput,
                        onValueChange = onTokenInputChange,
                        label = "Bearer token (optional – skips pairing)",
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    )
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
                "Connect Gateway",
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
                Text("Not connected", style = mobileHeadline, color = mobileTextSecondary)
                Text(
                    "Set up your gateway on the Connect tab first.",
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
                        "Go to Connect",
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
    onPickImages: () -> Unit,
    onRemoveAttachment: (id: String) -> Unit,
    onClearChat: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
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
                Text("Message…", style = mobileBody, color = mobileTextTertiary)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZcActionButton(
                icon = Icons.Default.AttachFile,
                label = "Attach",
                onClick = onPickImages,
            )
            ZcActionButton(
                icon = Icons.Default.Refresh,
                label = "Regenerate",
                onClick = onRefresh,
            )
            ZcActionButton(
                icon = Icons.Default.Stop,
                label = "Stop",
                onClick = onStop,
            )
            ZcActionButton(
                icon = Icons.Default.Delete,
                label = "Clear chat",
                onClick = onClearChat,
            )
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
                    "Send",
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
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = mobileCardSurface,
            contentColor = mobileTextSecondary,
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
        Text("Settings", style = mobileTitle1, color = mobileText)

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
                    "Gateway address",
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileTextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZcTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = "Host",
                        modifier = Modifier.weight(3f),
                        keyboardType = KeyboardType.Uri,
                    )
                    ZcTextField(
                        value = port.toString(),
                        onValueChange = onPortChange,
                        label = "Port",
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
                            "Saved token",
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
                            "Clear & Reset",
                            style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileDanger,
                        )
                    }
                } else {
                    Text(
                        "No token saved",
                        style = mobileCallout,
                        color = mobileTextTertiary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Message bubble – user / assistant / tool_call / tool_result / system_error
// ---------------------------------------------------------------------------
@Composable
private fun ZcMessageBubble(msg: ZcChatMessage) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool_call" || msg.role == "tool_result"
    val isError = msg.role == "system_error"

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
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = if (msg.role == "tool_call") "⚙ tool call" else "✓ tool result",
                            style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                            color = mobileTextTertiary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            msg.content,
                            style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
                            color = mobileTextSecondary,
                        )
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
                        .widthIn(max = 300.dp)
                        .alpha(pulseAlpha),
                ) {
                    Text(
                        text = msg.content.ifEmpty { "…" },
                        style = mobileBody,
                        color = mobileText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
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
