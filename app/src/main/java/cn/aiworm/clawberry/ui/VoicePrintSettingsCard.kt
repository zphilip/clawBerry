package clawberry.aiworm.cn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import clawberry.aiworm.cn.AppLanguage
import clawberry.aiworm.cn.MainViewModel
import clawberry.aiworm.cn.voice.SpeakerRegistrationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Self-contained Settings card for voice-print registration.
 *
 * Drop this composable into any settings list — it manages its own
 * [SpeakerRegistrationManager] and coroutine scope internally.
 *
 * The card uses the logged-in user's [displayName] as the friendly name and
 * the device [instanceId] as the stable speaker `user_id`.
 */
@Composable
fun VoicePrintSettingsCard(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { SpeakerRegistrationManager(context) }
    val ui by manager.uiState.collectAsState()

    val displayName by viewModel.displayName.collectAsState()
    val instanceId by viewModel.instanceId.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    // Clean up recording on exit
    DisposableEffect(Unit) { onDispose { manager.reset() } }

    // Ticker while recording
    LaunchedEffect(ui.state) {
        if (ui.state == SpeakerRegistrationManager.State.Recording) {
            while (true) {
                delay(1_000)
                manager.tickRecordingSecond()
            }
        }
    }

    // Auto-check registration status when first shown
    LaunchedEffect(instanceId) {
        if (instanceId.isNotBlank() && ui.state == SpeakerRegistrationManager.State.Idle) {
            manager.checkRegistered(instanceId, displayName, appLanguage)
        }
    }

    val listItemColors = ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = mobileText,
        supportingColor = mobileTextSecondary,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, mobileBorder, RoundedCornerShape(14.dp))
            .background(mobileCardSurface, RoundedCornerShape(14.dp)),
    ) {
        // ── Header row ──────────────────────────────────────────────────
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
                Text(
                    if (appLanguage == AppLanguage.ChineseSimplified) "声纹注册" else "Voice Print",
                    style = mobileHeadline,
                )
            },
            supportingContent = {
                Text(
                    when {
                        ui.state == SpeakerRegistrationManager.State.Checking ->
                            if (appLanguage == AppLanguage.ChineseSimplified) "正在检查…" else "Checking…"
                        ui.isRegistered ->
                            if (appLanguage == AppLanguage.ChineseSimplified) "声纹已注册" else "Voice print enrolled"
                        else ->
                            if (appLanguage == AppLanguage.ChineseSimplified) "未注册" else "Not enrolled"
                    },
                    style = mobileCallout,
                )
            },
            trailingContent = {
                // Status dot
                val dotColor by animateColorAsState(
                    targetValue = when {
                        ui.isRegistered -> Color(0xFF22C55E)
                        ui.state == SpeakerRegistrationManager.State.Checking -> Color(0xFFF59E0B)
                        else -> Color(0xFF6B7280)
                    },
                    label = "voiceprint_dot",
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape),
                )
            },
        )

        // Only show body when not idle/checking
        when (ui.state) {
            SpeakerRegistrationManager.State.Idle,
            SpeakerRegistrationManager.State.Checking -> { /* spinner handled by supporting text */ }

            SpeakerRegistrationManager.State.ReadyToRecord,
            SpeakerRegistrationManager.State.Registered -> {
                HorizontalDivider(color = mobileBorder)
                ReadyContent(
                    ui = ui,
                    appLanguage = appLanguage,
                    onRegister = {
                        scope.launch {
                            manager.checkRegistered(instanceId, displayName, appLanguage)
                            // prompt is refreshed; user will press Record next
                        }
                        manager.startRecording()
                    },
                    onDelete = {
                        scope.launch { manager.deleteVoicePrint(instanceId) }
                    },
                    onStartRecording = { manager.startRecording() },
                )
            }

            SpeakerRegistrationManager.State.Recording -> {
                HorizontalDivider(color = mobileBorder)
                RecordingContent(
                    ui = ui,
                    appLanguage = appLanguage,
                    onStop = {
                        scope.launch { manager.stopAndRegister(instanceId, displayName) }
                    },
                    onCancel = { manager.cancelRecording() },
                )
            }

            SpeakerRegistrationManager.State.Uploading -> {
                HorizontalDivider(color = mobileBorder)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (appLanguage == AppLanguage.ChineseSimplified) "正在上传声纹…" else "Uploading voice print…",
                        style = mobileCallout,
                        color = mobileTextSecondary,
                    )
                }
            }

            SpeakerRegistrationManager.State.Error -> {
                HorizontalDivider(color = mobileBorder)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        ui.error ?: "Unknown error",
                        style = mobileCallout,
                        color = mobileDanger,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                manager.checkRegistered(instanceId, displayName, appLanguage)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = mobileAccent,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (appLanguage == AppLanguage.ChineseSimplified) "重试" else "Retry",
                            style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    ui: SpeakerRegistrationManager.UiState,
    appLanguage: AppLanguage,
    onRegister: () -> Unit,
    onDelete: () -> Unit,
    onStartRecording: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Prompt text
        Text(
            ui.prompt.ifBlank {
                if (appLanguage == AppLanguage.ChineseSimplified)
                    "按下\u201C开始录音\u201D，然后朗读提示文字。"
                else
                    "Press \"Start Recording\" and read the prompt aloud."
            },
            style = mobileCallout.copy(fontStyle = FontStyle.Italic),
            color = mobileTextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = mobileAccent,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (appLanguage == AppLanguage.ChineseSimplified) "开始录音" else "Start Recording",
                    style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
            }
            if (ui.isRegistered) {
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileDanger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, mobileDanger),
                ) {
                    Text(
                        if (appLanguage == AppLanguage.ChineseSimplified) "删除声纹" else "Delete",
                        style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        if (ui.isRegistered) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (appLanguage == AppLanguage.ChineseSimplified)
                    "重新录音将覆盖已有声纹。"
                else
                    "Recording again will overwrite the existing voice print.",
                style = mobileCaption1,
                color = mobileTextTertiary,
            )
        }
    }
}

@Composable
private fun RecordingContent(
    ui: SpeakerRegistrationManager.UiState,
    appLanguage: AppLanguage,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    // Pulsing red dot while recording
    val pulse = rememberInfiniteTransition(label = "rec_pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "rec_alpha",
    )

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Prompt
        Text(
            ui.prompt,
            style = mobileCallout.copy(fontStyle = FontStyle.Italic),
            color = mobileText,
        )
        Spacer(Modifier.height(12.dp))
        // Recording indicator + timer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(alpha)
                    .background(mobileDanger, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            val remaining = SpeakerRegistrationManager.MAX_RECORD_SEC - ui.recordedSeconds
            Text(
                if (appLanguage == AppLanguage.ChineseSimplified)
                    "录音中… ${ui.recordedSeconds}s  (最多 ${SpeakerRegistrationManager.MAX_RECORD_SEC}s)"
                else
                    "Recording… ${ui.recordedSeconds}s  (max ${SpeakerRegistrationManager.MAX_RECORD_SEC}s)",
                style = mobileCallout,
                color = mobileDanger,
            )
        }
        Spacer(Modifier.height(12.dp))

        // Auto-stop when max reached
        LaunchedEffect(ui.recordedSeconds) {
            if (ui.recordedSeconds >= SpeakerRegistrationManager.MAX_RECORD_SEC) {
                onStop()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStop,
                enabled = ui.recordedSeconds >= SpeakerRegistrationManager.MIN_RECORD_SEC,
                colors = ButtonDefaults.buttonColors(
                    containerColor = mobileDanger,
                    contentColor = Color.White,
                    disabledContainerColor = mobileDanger.copy(alpha = 0.4f),
                    disabledContentColor = Color.White.copy(alpha = 0.8f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (appLanguage == AppLanguage.ChineseSimplified) "停止并注册" else "Stop & Register",
                    style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
            }
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileTextSecondary),
                border = androidx.compose.foundation.BorderStroke(1.dp, mobileBorder),
            ) {
                Text(
                    if (appLanguage == AppLanguage.ChineseSimplified) "取消" else "Cancel",
                    style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
        if (ui.recordedSeconds < SpeakerRegistrationManager.MIN_RECORD_SEC) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (appLanguage == AppLanguage.ChineseSimplified)
                    "请至少录音 ${SpeakerRegistrationManager.MIN_RECORD_SEC} 秒"
                else
                    "Record at least ${SpeakerRegistrationManager.MIN_RECORD_SEC} seconds",
                style = mobileCaption1,
                color = mobileTextTertiary,
            )
        }
    }
}
