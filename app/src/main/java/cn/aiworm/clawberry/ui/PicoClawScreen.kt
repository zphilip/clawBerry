package clawberry.aiworm.cn.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import clawberry.aiworm.cn.picoclaw.PcChatMessage
import clawberry.aiworm.cn.picoclaw.PcImageAttachment
import clawberry.aiworm.cn.picoclaw.PcMode
import clawberry.aiworm.cn.picoclaw.PcState
import clawberry.aiworm.cn.picoclaw.PicoClawViewModel
import clawberry.aiworm.cn.ui.chat.ChatMarkdown
import clawberry.aiworm.cn.ui.chat.PendingImageAttachment
import clawberry.aiworm.cn.ui.chat.loadSizedImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import clawberry.aiworm.cn.ui.chat.rememberBase64ImageState

// ---------------------------------------------------------------------------
// Sub-tab enum
// ---------------------------------------------------------------------------
private enum class PcTab(val label: String, val icon: ImageVector) {
    Connect(label = "Connect", icon = Icons.Default.CheckCircle),
    Chat(label = "Chat", icon = Icons.Default.ChatBubble),
    Settings(label = "Settings", icon = Icons.Default.Settings),
}

// ---------------------------------------------------------------------------
// Root entry point
// ---------------------------------------------------------------------------
@Composable
fun PicoClawScreen(viewModel: PicoClawViewModel) {
    val state by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val errorText by viewModel.errorText.collectAsState()
    val host by viewModel.host.collectAsState()
    val webPort by viewModel.webPort.collectAsState()
    val gatewayPort by viewModel.gatewayPort.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val token by viewModel.token.collectAsState()
    val tokenInput by viewModel.tokenInput.collectAsState()

    var activeTab by rememberSaveable { mutableStateOf(PcTab.Connect) }

    LaunchedEffect(state) {
        when (state) {
            PcState.Connected -> if (activeTab != PcTab.Chat) activeTab = PcTab.Chat
            PcState.Idle, PcState.Error -> if (activeTab == PcTab.Chat) activeTab = PcTab.Connect
            PcState.Reconnecting -> Unit // stay on current tab while auto-reconnecting
            else -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PcStatusBar(
            state = state,
            host = host,
            port = if (mode == PcMode.WebBackend) webPort else gatewayPort,
            mode = mode,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                PcTab.Connect -> PcConnectTab(
                    state = state,
                    errorText = errorText,
                    host = host,
                    webPort = webPort,
                    gatewayPort = gatewayPort,
                    mode = mode,
                    token = token,
                    tokenInput = tokenInput,
                    onHostChange = viewModel::setHost,
                    onWebPortChange = viewModel::setWebPort,
                    onGatewayPortChange = viewModel::setGatewayPort,
                    onModeChange = viewModel::setMode,
                    onTokenInputChange = viewModel::setTokenInput,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onGoChat = { activeTab = PcTab.Chat },
                )
                PcTab.Chat -> PcChatTab(
                    isConnected = state == PcState.Connected,
                    messages = messages,
                    onSend = { text, atts ->
                        viewModel.sendMessage(
                            text,
                            atts.map { PcImageAttachment(mimeType = it.mimeType, fileName = it.fileName, base64 = it.base64) },
                        )
                    },
                    onClearChat = viewModel::clearMessages,
                    onRefresh = viewModel::regenerateLastMessage,
                    onStop = viewModel::stopStreaming,
                    onGoConnect = { activeTab = PcTab.Connect },
                )
                PcTab.Settings -> PcSettingsTab(
                    host = host,
                    webPort = webPort,
                    gatewayPort = gatewayPort,
                    mode = mode,
                    token = token,
                    tokenInput = tokenInput,
                    onHostChange = viewModel::setHost,
                    onWebPortChange = viewModel::setWebPort,
                    onGatewayPortChange = viewModel::setGatewayPort,
                    onModeChange = viewModel::setMode,
                    onTokenInputChange = viewModel::setTokenInput,
                    onClearAndReset = viewModel::clearAndReset,
                )
            }
            VerticalTabRail(
                tabs = PcTab.entries,
                activeTab = activeTab,
                onSelect = { activeTab = it },
                icon = { tab -> tab.icon },
                label = { tab -> tab.label },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = if (activeTab == PcTab.Chat) 72.dp else 0.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status bar
// ---------------------------------------------------------------------------
@Composable
private fun PcStatusBar(
    state: PcState,
    host: String,
    port: Int,
    mode: PcMode,
) {
    val label: String
    val dotColor: Color
    val textColor: Color
    val bgColor: Color
    val borderColor: Color
    val modeTag = if (mode == PcMode.WebBackend) "web" else "direct"
    when (state) {
        PcState.Idle -> {
            label = "Idle · $modeTag"
            dotColor = mobileTextTertiary
            textColor = mobileTextSecondary
            bgColor = mobileSurface
            borderColor = mobileBorder
        }
        PcState.FetchingToken -> {
            label = "Fetching token…"
            dotColor = mobileAccent
            textColor = mobileAccent
            bgColor = mobileAccentSoft
            borderColor = LocalMobileColors.current.chipBorderConnecting
        }
        PcState.Connecting -> {
            label = "Connecting…"
            dotColor = mobileAccent
            textColor = mobileAccent
            bgColor = mobileAccentSoft
            borderColor = LocalMobileColors.current.chipBorderConnecting
        }
        PcState.Reconnecting -> {
            label = "Reconnecting…"
            dotColor = Color(0xFFFFA726)   // amber
            textColor = Color(0xFFFFA726)
            bgColor = Color(0x22FFA726)
            borderColor = Color(0x66FFA726)
        }
        PcState.Connected -> {
            label = "Connected · $host:$port"
            dotColor = mobileSuccess
            textColor = mobileSuccess
            bgColor = mobileSuccessSoft
            borderColor = LocalMobileColors.current.chipBorderConnected
        }
        PcState.Error -> {
            label = "Error"
            dotColor = mobileDanger
            textColor = mobileDanger
            bgColor = mobileDangerSoft
            borderColor = LocalMobileColors.current.chipBorderError
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
                    painter = painterResource(id = R.drawable.ic_picoclaw),
                    contentDescription = "PicoClaw",
                    modifier = Modifier.size(28.dp),
                )
                Text("PicoClaw", style = mobileTitle2, color = mobileText)
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
// Connect tab
// ---------------------------------------------------------------------------
@Composable
private fun PcConnectTab(
    state: PcState,
    errorText: String?,
    host: String,
    webPort: Int,
    gatewayPort: Int,
    mode: PcMode,
    token: String?,
    tokenInput: String,
    onHostChange: (String) -> Unit,
    onWebPortChange: (Int) -> Unit,
    onGatewayPortChange: (Int) -> Unit,
    onModeChange: (PcMode) -> Unit,
    onTokenInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onGoChat: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val qrContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var qrScanError by remember { mutableStateOf<String?>(null) }
    var qrScanSuccess by remember { mutableStateOf<String?>(null) }  // message after successful scan
    var showPhotoPermissionDialog by remember { mutableStateOf(false) }

    // ── Helper: apply parsed QR and show success ──────────────────────────────
    fun applyQrResult(raw: String): Boolean {
        val qr = parsePicoClawQr(raw)
        if (qr.host == null) {
            qrScanError = "QR not recognised — expected picoclaw:// URL or JSON"
            qrScanSuccess = null
            return false
        }
        qrScanError = null
        onHostChange(qr.host)
        qr.mode?.let(onModeChange)
        qr.webPort?.let(onWebPortChange)
        qr.gatewayPort?.let(onGatewayPortChange)
        qr.token?.let(onTokenInputChange)
        qrScanSuccess = "QR applied — host: ${qr.host}  tap Connect to continue"
        return true
    }

    // ── ZXing camera QR scanner launcher ──────────────────────────────────────
    val zxingLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        qrScanError = null
        qrScanSuccess = null
        val raw = result.contents
        if (raw != null) {
            applyQrResult(raw)
        }
        // null means user pressed back / cancelled — do nothing
    }

    // ── ML Kit static barcode detector (for gallery images) ──
    val mlkitDetector = remember { BarcodeScanning.getClient() }

    // ── Photo permission + gallery picker launchers ───────────────────────────
    val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(qrContext, uri)
                mlkitDetector.process(image)
                    .addOnSuccessListener { barcodes ->
                        val raw = barcodes.firstOrNull()?.rawValue
                        if (raw != null) {
                            applyQrResult(raw)
                        } else {
                            qrScanError = "No QR code found in the selected photo"
                            qrScanSuccess = null
                        }
                    }
                    .addOnFailureListener { e ->
                        qrScanError = "Could not read photo: ${e.localizedMessage ?: "Unknown error"}"
                        qrScanSuccess = null
                    }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    qrScanError = "Could not open photo: ${e.localizedMessage ?: "Unknown error"}"
                    qrScanSuccess = null
                }
            }
        }
    }

    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            galleryLauncher.launch("image/*")
        } else {
            showPhotoPermissionDialog = true
        }
    }

    fun launchGalleryPicker() {
        qrScanError = null
        qrScanSuccess = null
        val alreadyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            qrContext, photoPermission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            galleryLauncher.launch("image/*")
        } else {
            photoPermissionLauncher.launch(photoPermission)
        }
    }

    // ── Permission-denied explanation dialog ──────────────────────────────────
    if (showPhotoPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoPermissionDialog = false },
            title = { Text("Photo Permission Required", color = mobileText) },
            text = {
                Text(
                    "To scan a QR code from your gallery, ClawBerry needs access to your photos.\n\n" +
                    "Please grant the permission in Settings → Apps → ClawBerry → Permissions.",
                    color = mobileTextSecondary,
                    style = mobileCallout,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoPermissionDialog = false
                    // Open app settings so the user can grant it manually
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", qrContext.packageName, null)
                    )
                    qrContext.startActivity(intent)
                }) { Text("Open Settings", color = mobileAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoPermissionDialog = false }) {
                    Text("Cancel", color = mobileTextSecondary)
                }
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "PicoClaw Gateway",
            color = mobileText,
            style = mobileTitle2.copy(fontWeight = FontWeight.Bold),
        )

        PcInfoCard(
            state = state,
            host = host,
            webPort = webPort,
            gatewayPort = gatewayPort,
            mode = mode,
            token = token,
        )

        if (state == PcState.Error && errorText != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0x22EF5350),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "! $errorText",
                    color = Color(0xFFEF5350),
                    style = mobileCallout,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state == PcState.Reconnecting && errorText != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0x22FFA726),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFFFA726).copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "↻ $errorText",
                    color = Color(0xFFFFA726),
                    style = mobileCallout,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        val isIdle = state == PcState.Idle || state == PcState.Error

        if (isIdle) {
            // ── QR scan buttons ───────────────────────────────────────────────
            // Button 1: camera live scan (GMS Code Scanner)
            OutlinedButton(
                onClick = {
                    qrScanError = null
                    qrScanSuccess = null
                    zxingLauncher.launch(
                        ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Point at a PicoClaw QR code")
                            setBeepEnabled(true)
                            setBarcodeImageEnabled(false)
                            setCaptureActivity(clawberry.aiworm.cn.PortraitCaptureActivity::class.java)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Scan QR with Camera", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }

            // Button 2: pick QR from gallery photo
            OutlinedButton(
                onClick = { launchGalleryPicker() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Pick QR from Gallery", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }

            // Scan result feedback
            if (qrScanSuccess != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x2200C853),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF00C853).copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = "✓ $qrScanSuccess",
                        color = Color(0xFF00C853),
                        style = mobileCallout,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (qrScanError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x22EF5350),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "⚠ $qrScanError",
                        color = Color(0xFFEF5350),
                        style = mobileCaption1,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // ── Network discovery ─────────────────────────────────────────
            FindClawGatewayCard(
                serviceTypeFilter = clawberry.aiworm.cn.gateway.ClawServiceType.ATTR_PICOCLAW,
                onGatewaySelected = { selectedHost, selectedPort, _ ->
                    onHostChange(selectedHost)
                    onWebPortChange(selectedPort)
                    onGatewayPortChange(selectedPort)
                },
            )

            // ── or fill manually ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = mobileBorder)
                Text(text = "or fill manually", color = mobileTextTertiary, style = mobileCaption1)
                HorizontalDivider(modifier = Modifier.weight(1f), color = mobileBorder)
            }

            PcModeSelector(mode = mode, onModeChange = onModeChange)
            PcSetupForm(
                mode = mode,
                host = host,
                webPort = webPort,
                gatewayPort = gatewayPort,
                tokenInput = tokenInput,
                onHostChange = onHostChange,
                onWebPortChange = onWebPortChange,
                onGatewayPortChange = onGatewayPortChange,
                onTokenInputChange = onTokenInputChange,
                onConnect = onConnect,
            )
        }

        if (state == PcState.FetchingToken || state == PcState.Connecting || state == PcState.Reconnecting) {
            val msg = when (state) {
                PcState.FetchingToken -> "Fetching token from gateway..."
                PcState.Reconnecting -> "Waiting to reconnect..."
                else -> "Opening WebSocket..."
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = mobileAccent)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = msg, color = mobileTextSecondary, style = mobileCallout)
            }
        }

        if (state == PcState.Reconnecting) {
            PcActionButton(
                label = "Cancel Reconnect",
                icon = Icons.Default.PowerSettingsNew,
                onClick = onDisconnect,
                isPrimary = false,
                tint = Color(0xFFFFA726),
            )
        }

        if (state == PcState.Connected) {
            PcActionButton(
                label = "Go to Chat",
                icon = Icons.Default.ChatBubble,
                onClick = onGoChat,
                isPrimary = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            PcActionButton(
                label = "Disconnect",
                icon = Icons.Default.PowerSettingsNew,
                onClick = onDisconnect,
                isPrimary = false,
                tint = Color(0xFFEF5350),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Info card
// ---------------------------------------------------------------------------
@Composable
private fun PcInfoCard(
    state: PcState,
    host: String,
    webPort: Int,
    gatewayPort: Int,
    mode: PcMode,
    token: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = mobileCardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Endpoint", color = mobileTextSecondary, style = mobileCallout)
                val portLabel = if (mode == PcMode.WebBackend) ":$webPort (web)" else ":$gatewayPort (direct)"
                Text(
                    text = "$host$portLabel",
                    color = mobileText,
                    style = mobileCallout.copy(fontWeight = FontWeight.Medium),
                )
            }
            HorizontalDivider(color = mobileBorder, thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Mode", color = mobileTextSecondary, style = mobileCallout)
                Text(
                    text = if (mode == PcMode.WebBackend) "Web Backend" else "Direct",
                    color = if (mode == PcMode.WebBackend) mobileAccent else Color(0xFF9C27B0),
                    style = mobileCallout.copy(fontWeight = FontWeight.Medium),
                )
            }
            HorizontalDivider(color = mobileBorder, thickness = 0.5.dp)

            val sdot: String
            val slabel: String
            val scolor: Color
            when (state) {
                PcState.Idle -> {
                    sdot = "o"; slabel = "Not connected"; scolor = mobileTextTertiary
                }
                PcState.FetchingToken -> {
                    sdot = "~"; slabel = "Fetching token..."; scolor = mobileAccent
                }
                PcState.Connecting -> {
                    sdot = "~"; slabel = "Connecting..."; scolor = mobileAccent
                }
                PcState.Reconnecting -> {
                    sdot = "↻"; slabel = "Reconnecting..."; scolor = Color(0xFFFFA726)
                }
                PcState.Connected -> {
                    sdot = "*"; slabel = "Connected"; scolor = Color(0xFF4CAF50)
                }
                PcState.Error -> {
                    sdot = "!"; slabel = "Error"; scolor = Color(0xFFEF5350)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Status", color = mobileTextSecondary, style = mobileCallout)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = sdot, color = scolor, style = mobileCallout)
                    Text(text = slabel, color = scolor, style = mobileCallout.copy(fontWeight = FontWeight.Medium))
                }
            }

            if (token != null && token.isNotBlank()) {
                HorizontalDivider(color = mobileBorder, thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Token", color = mobileTextSecondary, style = mobileCallout)
                    Text(
                        text = if (token.length > 12) "${token.take(8)}...${token.takeLast(4)}" else "********",
                        color = mobileTextTertiary,
                        style = mobileCallout,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mode selector
// ---------------------------------------------------------------------------
@Composable
private fun PcModeSelector(mode: PcMode, onModeChange: (PcMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Connection Mode", color = mobileTextSecondary, style = mobileCaption1)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PcModeChip(
                label = "Web Backend",
                description = "Port 18800",
                selected = mode == PcMode.WebBackend,
                onClick = { onModeChange(PcMode.WebBackend) },
                modifier = Modifier.weight(1f),
            )
            PcModeChip(
                label = "Direct",
                description = "Port 18790",
                selected = mode == PcMode.Direct,
                onClick = { onModeChange(PcMode.Direct) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PcModeChip(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) mobileAccentSoft else mobileCardSurface,
        border = BorderStroke(
            1.dp,
            if (selected) LocalMobileColors.current.chipBorderConnecting else mobileBorder,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = if (selected) mobileAccent else mobileText,
                style = mobileCallout.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            )
            Text(
                text = description,
                color = if (selected) mobileAccent.copy(alpha = 0.7f) else mobileTextTertiary,
                style = mobileCaption1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Setup form
// ---------------------------------------------------------------------------
@Composable
private fun PcSetupForm(
    mode: PcMode,
    host: String,
    webPort: Int,
    gatewayPort: Int,
    tokenInput: String,
    onHostChange: (String) -> Unit,
    onWebPortChange: (Int) -> Unit,
    onGatewayPortChange: (Int) -> Unit,
    onTokenInputChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PcTextField(
            value = host,
            onValueChange = onHostChange,
            label = "Gateway Host",
            placeholder = "192.168.1.100",
            keyboardType = KeyboardType.Uri,
        )

        if (mode == PcMode.WebBackend) {
            PcTextField(
                value = webPort.toString(),
                onValueChange = { v ->
                    onWebPortChange(v.filter(Char::isDigit).take(5).toIntOrNull() ?: webPort)
                },
                label = "Web Port",
                placeholder = "18800",
                keyboardType = KeyboardType.Number,
            )
            Text(
                text = "Token will be fetched automatically from the gateway.",
                color = mobileTextTertiary,
                style = mobileCaption1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        } else {
            PcTextField(
                value = gatewayPort.toString(),
                onValueChange = { v ->
                    onGatewayPortChange(v.filter(Char::isDigit).take(5).toIntOrNull() ?: gatewayPort)
                },
                label = "Gateway Port",
                placeholder = "18790",
                keyboardType = KeyboardType.Number,
            )
            PcTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                label = "Bearer Token",
                placeholder = "Paste your token here",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
                onImeAction = onConnect,
            )
        }

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileAccent),
        ) {
            Icon(imageVector = Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Connect", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
        }
    }
}

// ---------------------------------------------------------------------------
// Chat tab
// ---------------------------------------------------------------------------
@Composable
private fun PcChatTab(
    isConnected: Boolean,
    messages: List<PcChatMessage>,
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lan,
                    contentDescription = null,
                    tint = mobileTextTertiary,
                    modifier = Modifier.size(40.dp).alpha(0.5f),
                )
                Text(text = "Not connected", color = mobileTextTertiary, style = mobileBody)
                TextButton(onClick = onGoConnect) {
                    Text(text = "Go to Connect", color = mobileAccent, style = mobileCallout)
                }
            }
        }
        return
    }

    val attachments = remember { mutableStateListOf<PendingImageAttachment>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var fullscreenImage by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val onImageClick: (String, String?) -> Unit = { b64, mime -> fullscreenImage = b64 to mime }
    val resolver = context.contentResolver
    val listState = rememberLazyListState()

    val pickImages =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val next = uris.take(8).mapNotNull { uri ->
                    try { loadSizedImageAttachment(resolver, uri) } catch (_: Throwable) { null }
                }
                withContext(Dispatchers.Main) { attachments.addAll(next) }
            }
        }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                PcMessageBubble(msg = msg, onImageClick = onImageClick)
            }
        }
        HorizontalDivider(color = mobileBorder, thickness = 0.5.dp)
        PcComposer(
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
    fullscreenImage?.let { (base64, mimeType) ->
        PcFullscreenImageDialog(base64 = base64, mimeType = mimeType, onDismiss = { fullscreenImage = null })
    }
}

// ---------------------------------------------------------------------------
// Message bubble
// ---------------------------------------------------------------------------
@Composable
private fun PcMessageBubble(msg: PcChatMessage, onImageClick: (String, String?) -> Unit) {
    when (msg.role) {
        "user" -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.widthIn(max = 280.dp),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = mobileAccent,
                ) {
                    SelectionContainer {
                        Text(
                            text = msg.content,
                            color = Color.White,
                            style = mobileBody,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        "assistant" -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    modifier = Modifier.widthIn(max = 320.dp),
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = mobileCardSurface,
                    border = BorderStroke(1.dp, mobileBorder),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        ChatMarkdown(
                            text = msg.content,
                            textColor = mobileText,
                            onImageClick = onImageClick,
                        )
                    }
                }
            }
        }
        "typing" -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                PcTypingIndicator()
            }
        }
        "system_error" -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0x22EF5350),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.35f)),
            ) {
                Text(
                    text = "! ${msg.content}",
                    color = Color(0xFFEF5350),
                    style = mobileCaption1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Typing indicator
// ---------------------------------------------------------------------------
@Composable
private fun PcTypingIndicator() {
    Surface(
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "typingDots")
            repeat(3) { i ->
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500, delayMillis = i * 160, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Surface(
                    modifier = Modifier.size(7.dp).alpha(alpha),
                    shape = RoundedCornerShape(50),
                    color = mobileAccent,
                ) {}
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------
@Composable
private fun PcComposer(
    attachments: List<PendingImageAttachment>,
    onPickImages: () -> Unit,
    onRemoveAttachment: (id: String) -> Unit,
    onClearChat: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Attachment chips
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { att ->
                    PcAttachmentChip(
                        fileName = att.fileName,
                        onRemove = { onRemoveAttachment(att.id) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Message PicoClaw...",
                    color = mobileTextTertiary,
                    style = mobileCallout,
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = {
                if (canSend) { onSend(text); text = "" }
            }),
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = mobileAccent,
                unfocusedBorderColor = mobileBorder,
                focusedTextColor = mobileText,
                unfocusedTextColor = mobileText,
                cursorColor = mobileAccent,
                focusedContainerColor = mobileCardSurface,
                unfocusedContainerColor = mobileCardSurface,
            ),
            textStyle = mobileCallout,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PcIconButton(icon = Icons.Default.AttachFile, label = "Attach", onClick = onPickImages)
            PcIconButton(icon = Icons.Default.Refresh, label = "Regenerate", onClick = onRefresh)
            PcIconButton(icon = Icons.Default.Stop, label = "Stop", onClick = onStop)
            PcIconButton(icon = Icons.Default.Delete, label = "Clear", onClick = onClearChat)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { if (canSend) { onSend(text); text = "" } },
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (canSend) mobileAccent else mobileCardSurface,
                border = BorderStroke(1.dp, if (canSend) mobileAccent else mobileBorder),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else mobileTextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PcAttachmentChip(fileName: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = mobileAccentSoft,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = fileName,
                style = mobileCaption1,
                color = mobileText,
                maxLines = 1,
            )
            Surface(
                onClick = onRemove,
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(14.dp),
                    tint = mobileTextSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings tab
// ---------------------------------------------------------------------------
@Composable
private fun PcSettingsTab(
    host: String,
    webPort: Int,
    gatewayPort: Int,
    mode: PcMode,
    token: String?,
    tokenInput: String,
    onHostChange: (String) -> Unit,
    onWebPortChange: (Int) -> Unit,
    onGatewayPortChange: (Int) -> Unit,
    onModeChange: (PcMode) -> Unit,
    onTokenInputChange: (String) -> Unit,
    onClearAndReset: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "PicoClaw Settings",
            color = mobileText,
            style = mobileTitle2.copy(fontWeight = FontWeight.Bold),
        )

        PcSettingsSection(title = "Connection Mode") {
            PcModeSelector(mode = mode, onModeChange = onModeChange)
        }

        PcSettingsSection(title = "Gateway Address") {
            PcTextField(
                value = host,
                onValueChange = onHostChange,
                label = "Host",
                placeholder = "192.168.1.100",
                keyboardType = KeyboardType.Uri,
            )
            PcTextField(
                value = webPort.toString(),
                onValueChange = { v ->
                    onWebPortChange(v.filter(Char::isDigit).take(5).toIntOrNull() ?: webPort)
                },
                label = "Web Backend Port",
                placeholder = "18800",
                keyboardType = KeyboardType.Number,
            )
            PcTextField(
                value = gatewayPort.toString(),
                onValueChange = { v ->
                    onGatewayPortChange(v.filter(Char::isDigit).take(5).toIntOrNull() ?: gatewayPort)
                },
                label = "Direct Gateway Port",
                placeholder = "18790",
                keyboardType = KeyboardType.Number,
            )
        }

        PcSettingsSection(title = "Authentication Token") {
            if (token != null && token.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = mobileCardSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, mobileBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "Saved Token", color = mobileTextSecondary, style = mobileCaption1)
                        Text(
                            text = if (token.length > 12) "${token.take(12)}...${token.takeLast(4)}" else "******",
                            color = mobileTextTertiary,
                            style = mobileCallout,
                        )
                    }
                }
            }
            PcTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                label = "Token (Direct Mode)",
                placeholder = "Paste bearer token for direct mode",
                keyboardType = KeyboardType.Password,
            )
        }

        PcSettingsSection(title = "Reset") {
            PcActionButton(
                label = "Clear Token & Disconnect",
                icon = Icons.Default.PowerSettingsNew,
                onClick = onClearAndReset,
                isPrimary = false,
                tint = Color(0xFFEF5350),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PcSettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = mobileTextTertiary, style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
        content()
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------
@Composable
private fun PcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label, color = mobileTextSecondary, style = mobileCaption1) },
        placeholder = { Text(text = placeholder, color = mobileTextTertiary, style = mobileCallout) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
        ),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = mobileAccent,
            unfocusedBorderColor = mobileBorder,
            focusedLabelColor = mobileAccent,
            unfocusedLabelColor = mobileTextSecondary,
            focusedTextColor = mobileText,
            unfocusedTextColor = mobileText,
            cursorColor = mobileAccent,
            focusedContainerColor = mobileCardSurface,
            unfocusedContainerColor = mobileCardSurface,
        ),
        textStyle = mobileCallout,
    )
}

@Composable
private fun PcIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = mobileTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PcActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isPrimary: Boolean,
    tint: Color = mobileAccent,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPrimary) tint else Color.Transparent,
            contentColor = if (isPrimary) Color.White else tint,
        ),
        border = if (!isPrimary) BorderStroke(1.dp, tint.copy(alpha = 0.5f)) else null,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------------------
// Fullscreen image dialog
// ---------------------------------------------------------------------------
@Composable
private fun PcFullscreenImageDialog(base64: String, mimeType: String?, onDismiss: () -> Unit) {
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
                "Tap to close",
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
// QR code parsing helpers
// Supported formats:
//   1. picoclaw://host:port?token=xxx[&mode=web|direct]
//   2. http(s)://host:port?token=xxx[&mode=web|direct]
//   3. JSON {"host":"...","webPort":18800,"gatewayPort":18790,"token":"...","mode":"web|direct"}
// ---------------------------------------------------------------------------
private data class PcQrResult(
    val host: String?,
    val webPort: Int?,
    val gatewayPort: Int?,
    val token: String?,
    val mode: PcMode?,
)

private fun parsePicoClawQr(raw: String): PcQrResult {
    val trimmed = raw.trim()

    // 1. URI format (picoclaw://, http://, https://)
    runCatching {
        val uri = android.net.Uri.parse(trimmed)
        if (uri?.host?.isNotBlank() == true) {
            val host = uri.host!!
            val port = if (uri.port != -1) uri.port else null
            val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("t")
            val modeStr = uri.getQueryParameter("mode") ?: uri.getQueryParameter("m")
            val mode = when (modeStr?.lowercase()) {
                "direct", "d" -> PcMode.Direct
                "web", "w", "webbackend" -> PcMode.WebBackend
                else -> if (port == 18790) PcMode.Direct else PcMode.WebBackend
            }
            return PcQrResult(
                host = host,
                webPort = if (mode == PcMode.WebBackend) port else null,
                gatewayPort = if (mode == PcMode.Direct) port else null,
                token = token,
                mode = mode,
            )
        }
    }

    // 2. JSON format {"host":"...","webPort":...,"token":"...","mode":"web"}
    runCatching {
        val jsonHost = Regex("\"host\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
        if (jsonHost?.isNotBlank() == true) {
            val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
            val webPort = Regex("\"webPort\"\\s*:\\s*(\\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            val gwPort = Regex("\"gatewayPort\"\\s*:\\s*(\\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\"port\"\\s*:\\s*(\\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            val modeStr = Regex("\"mode\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
            val mode = when (modeStr?.lowercase()) {
                "direct" -> PcMode.Direct
                "web", "webbackend" -> PcMode.WebBackend
                else -> if (gwPort != null) PcMode.Direct else PcMode.WebBackend
            }
            return PcQrResult(
                host = jsonHost,
                webPort = webPort,
                gatewayPort = gwPort,
                token = token,
                mode = mode,
            )
        }
    }

    return PcQrResult(null, null, null, null, null)
}
