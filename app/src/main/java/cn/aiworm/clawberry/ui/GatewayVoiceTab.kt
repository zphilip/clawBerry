package clawberry.aiworm.cn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import clawberry.aiworm.cn.R
import clawberry.aiworm.cn.voice.VoiceConversationEntry
import clawberry.aiworm.cn.voice.VoiceConversationRole
import kotlin.math.max

private fun Context.findActivityForVoice(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.hasRecordAudioPermissionForVoice() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun openAppSettingsForVoice(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(intent)
}

/**
 * Always-listening voice tab for ZeroClaw / PicoClaw.
 * Mirrors OpenClaw's VoiceTabScreen but driven by individual props so it works
 * with any gateway ViewModel.
 */
@Composable
fun GatewayVoiceTab(
    gatewayName: String,
    statusText: String,
    isConnected: Boolean,
    micEnabled: Boolean,
    micCooldown: Boolean,
    isListening: Boolean,
    micStatusText: String,
    liveTranscript: String?,
    conversation: List<VoiceConversationEntry>,
    inputLevel: Float,
    isSending: Boolean,
    useCustomAsr: Boolean = false,
    asrUrl: String = "",
    onSetUseCustomAsr: ((Boolean) -> Unit)? = null,
    useIdentityAsr: Boolean = false,
    voicePrintRegistered: Boolean = false,
    onSetUseIdentityAsr: (() -> Unit)? = null,
    kwsEnabled: Boolean = false,
    onSetKwsEnabled: ((Boolean) -> Unit)? = null,
    onSetMicEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivityForVoice() }
    val listState = rememberLazyListState()

    val animatedRingLevel by animateFloatAsState(
        targetValue = if (micEnabled) inputLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "micRingLevel",
    )

    val hasStreamingAssistant = conversation.any {
        it.role == VoiceConversationRole.Assistant && it.isStreaming
    }
    val showThinkingBubble = isSending && !hasStreamingAssistant

    var hasMicPermission by remember { mutableStateOf(context.hasRecordAudioPermissionForVoice()) }
    var pendingMicEnable by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = context.hasRecordAudioPermissionForVoice()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestMicPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            if (granted && pendingMicEnable) onSetMicEnabled(true)
            pendingMicEnable = false
        }

    LaunchedEffect(conversation.size, showThinkingBubble) {
        val total = conversation.size + if (showThinkingBubble) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(mobileBackgroundGradient)
            .imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorder),
        ) {
            Text(
                "$gatewayName Voice",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                color = mobileTextSecondary,
            )
        }

        // ── Conversation bubbles ──────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (conversation.isEmpty() && !showThinkingBubble) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = mobileTextTertiary,
                            )
                            Text(
                                stringResource(R.string.openclaw_tap_mic_hint),
                                style = mobileHeadline,
                                color = mobileTextSecondary,
                            )
                            Text(
                                stringResource(R.string.openclaw_pause_sends_turn),
                                style = mobileCallout,
                                color = mobileTextTertiary,
                            )
                        }
                    }
                }
            }

            items(items = conversation, key = { it.id }) { entry ->
                GatewayVoiceTurnBubble(entry = entry)
            }

            if (showThinkingBubble) {
                item { GatewayVoiceThinkingBubble() }
            }
        }

        // ── Controls ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Live transcript preview
            if (!liveTranscript.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = mobileAccentSoft,
                    border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.2f)),
                ) {
                    Text(
                        liveTranscript.trim(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = mobileCallout,
                        color = mobileText,
                    )
                }
            }

            // Mic button with reactive ring
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp).size(90.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (micEnabled || animatedRingLevel > 0.01f) {
                        val effectiveLevel = max(animatedRingLevel, if (micEnabled) 0.05f else 0f)
                        val ringSize = 68.dp + (22.dp * effectiveLevel)
                        Box(
                            modifier = Modifier
                                .size(ringSize)
                                .background(
                                    mobileAccent.copy(alpha = 0.12f + 0.14f * effectiveLevel),
                                    CircleShape,
                                ),
                        )
                    }
                    Button(
                        onClick = {
                            if (micCooldown) return@Button
                            if (micEnabled) {
                                onSetMicEnabled(false)
                            } else if (hasMicPermission) {
                                onSetMicEnabled(true)
                            } else {
                                pendingMicEnable = true
                                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !micCooldown && isConnected,
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                micCooldown -> mobileTextSecondary
                                micEnabled -> mobileDanger
                                else -> mobileAccent
                            },
                            contentColor = Color.White,
                            disabledContainerColor = mobileTextSecondary,
                            disabledContentColor = Color.White.copy(alpha = 0.5f),
                        ),
                    ) {
                        Icon(
                            imageVector = if (micEnabled) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (micEnabled)
                                stringResource(R.string.openclaw_mic_turn_off)
                            else
                                stringResource(R.string.openclaw_mic_turn_on),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                if (onSetUseCustomAsr != null) {
                    val funAsrAvailable = asrUrl.isNotBlank()
                    val builtInActive = !useCustomAsr && !useIdentityAsr
                    var asrExpanded by remember { mutableStateOf(false) }
                    val activeAsrLabel = when {
                        kwsEnabled -> "唤醒词"
                        useIdentityAsr -> if (voicePrintRegistered) "FunASR-ID" else "FunASR-ID !"
                        useCustomAsr -> if (funAsrAvailable) "FunASR" else "FunASR !"
                        else -> "Built-in"
                    }
                    Surface(
                        onClick = { asrExpanded = !asrExpanded },
                        shape = RoundedCornerShape(999.dp),
                        color = mobileSurface,
                        border = BorderStroke(1.dp, mobileBorder),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            AnimatedVisibility(
                                visible = !asrExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Text(
                                    activeAsrLabel,
                                    modifier = Modifier
                                        .background(mobileAccent, RoundedCornerShape(999.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                )
                            }
                            AnimatedVisibility(
                                visible = asrExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Surface(
                                        onClick = { onSetUseCustomAsr(false); asrExpanded = false },
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (builtInActive) mobileAccent else Color.Transparent,
                                    ) {
                                        Text(
                                            "Built-in",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (builtInActive) Color.White else mobileTextSecondary,
                                        )
                                    }
                                    Surface(
                                        onClick = { onSetUseCustomAsr(true); asrExpanded = false },
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (useCustomAsr && !useIdentityAsr) mobileAccent else Color.Transparent,
                                    ) {
                                        Text(
                                            if (funAsrAvailable) "FunASR" else "FunASR !",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                                            color = when {
                                                useCustomAsr && !useIdentityAsr -> Color.White
                                                funAsrAvailable -> mobileTextSecondary
                                                else -> mobileWarning
                                            },
                                        )
                                    }
                                    if (onSetUseIdentityAsr != null) {
                                        Surface(
                                            onClick = { if (voicePrintRegistered) { onSetUseIdentityAsr(); asrExpanded = false } },
                                            shape = RoundedCornerShape(999.dp),
                                            color = if (useIdentityAsr) mobileAccent else Color.Transparent,
                                        ) {
                                            Text(
                                                if (voicePrintRegistered) "FunASR-ID" else "FunASR-ID !",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                                                color = when {
                                                    useIdentityAsr -> Color.White
                                                    voicePrintRegistered -> mobileTextSecondary
                                                    else -> mobileWarning
                                                },
                                            )
                                        }
                                    }
                                    if (onSetKwsEnabled != null) {
                                        Surface(
                                            onClick = { onSetKwsEnabled(!kwsEnabled); asrExpanded = false },
                                            shape = RoundedCornerShape(999.dp),
                                            color = if (kwsEnabled) mobileAccent else Color.Transparent,
                                        ) {
                                            Text(
                                                "唤醒词",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (kwsEnabled) Color.White else mobileTextSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Status pill
            val stateColor = when {
                micEnabled -> mobileSuccess
                isListening -> mobileSuccess
                isSending -> mobileAccent
                else -> mobileTextSecondary
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (micEnabled) mobileSuccessSoft else mobileSurface,
                border = BorderStroke(
                    1.dp,
                    if (micEnabled) mobileSuccess.copy(alpha = 0.3f) else mobileBorder,
                ),
            ) {
                Text(
                    "$statusText · $micStatusText",
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = stateColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // Mic permission guidance
            if (!hasMicPermission) {
                val showRationale = if (activity == null) false
                    else ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO,
                    )
                Text(
                    if (showRationale) stringResource(R.string.openclaw_voice_permission_required)
                    else stringResource(R.string.openclaw_voice_permission_blocked),
                    style = mobileCaption1,
                    color = mobileWarning,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { openAppSettingsForVoice(context) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mobileSurfaceStrong,
                        contentColor = mobileText,
                    ),
                ) {
                    Text(
                        stringResource(R.string.common_open_settings),
                        style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun GatewayVoiceThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.68f),
            shape = RoundedCornerShape(12.dp),
            color = mobileCardSurface,
            border = BorderStroke(1.dp, mobileBorderStrong),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GatewayThinkingDots(color = mobileTextSecondary)
                Text(
                    stringResource(R.string.openclaw_thinking),
                    style = mobileCallout,
                    color = mobileTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun GatewayThinkingDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        GatewayThinkingDot(alpha = 0.38f, color = color)
        GatewayThinkingDot(alpha = 0.62f, color = color)
        GatewayThinkingDot(alpha = 0.90f, color = color)
    }
}

@Composable
private fun GatewayThinkingDot(alpha: Float, color: Color) {
    Surface(
        modifier = Modifier.size(6.dp).alpha(alpha),
        shape = CircleShape,
        color = color,
    ) {}
}

@Composable
private fun GatewayVoiceTurnBubble(entry: VoiceConversationEntry) {
    val isUser = entry.role == VoiceConversationRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.90f),
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) mobileAccentSoft else mobileCardSurface,
            border = BorderStroke(1.dp, if (isUser) mobileAccent else mobileBorderStrong),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (isUser) "You" else "Assistant",
                    style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isUser) mobileAccent else mobileTextSecondary,
                )
                Text(
                    entry.text + if (entry.isStreaming) "▌" else "",
                    style = mobileBody,
                    color = mobileText,
                )
            }
        }
    }
}