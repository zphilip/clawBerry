package clawberry.aiworm.cn.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clawberry.aiworm.cn.R
import clawberry.aiworm.cn.asr.AsrClient
import clawberry.aiworm.cn.asr.VoiceRecorder
import clawberry.aiworm.cn.ui.mobileAccent
import clawberry.aiworm.cn.ui.mobileAccentBorderStrong
import clawberry.aiworm.cn.ui.mobileAccentSoft
import clawberry.aiworm.cn.ui.mobileBorder
import clawberry.aiworm.cn.ui.mobileBorderStrong
import clawberry.aiworm.cn.ui.mobileCallout
import clawberry.aiworm.cn.ui.mobileCaption1
import clawberry.aiworm.cn.ui.mobileCardSurface
import clawberry.aiworm.cn.ui.mobileHeadline
import clawberry.aiworm.cn.ui.mobileSurface
import clawberry.aiworm.cn.ui.mobileText
import clawberry.aiworm.cn.ui.mobileTextSecondary
import clawberry.aiworm.cn.ui.mobileTextTertiary

/** Voice-input state for the push-to-talk mic button. */
internal enum class AsrVoiceState { Idle, Recording, Transcribing, Failed }

@Composable
fun ChatComposer(
  healthOk: Boolean,
  thinkingLevel: String,
  pendingRunCount: Int,
  attachments: List<PendingImageAttachment>,
  asrUrl: String,
  onPickImages: () -> Unit,
  onRemoveAttachment: (id: String) -> Unit,
  onSetThinkingLevel: (level: String) -> Unit,
  onRefresh: () -> Unit,
  onAbort: () -> Unit,
  onSend: (text: String) -> Unit,
) {
  var input by rememberSaveable { mutableStateOf("") }
  var showThinkingMenu by remember { mutableStateOf(false) }
  var voiceState by remember { mutableStateOf(AsrVoiceState.Idle) }
  var capturedPcm by remember { mutableStateOf<ByteArray?>(null) }
  var asrMode by rememberSaveable { mutableStateOf("2pass") }
  var transcribeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  val voiceRecorder = remember { VoiceRecorder() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  DisposableEffect(Unit) { onDispose { voiceRecorder.release() } }

  val canSend = pendingRunCount == 0 && (input.trim().isNotEmpty() || attachments.isNotEmpty()) && healthOk
  val sendBusy = pendingRunCount > 0

  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (attachments.isNotEmpty()) {
      AttachmentsStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
    }

    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text(stringResource(R.string.openclaw_chat_placeholder), style = mobileBodyStyle(), color = mobileTextTertiary) },
      minLines = 2,
      maxLines = 5,
      textStyle = mobileBodyStyle().copy(color = mobileText),
      shape = RoundedCornerShape(14.dp),
      colors = chatTextFieldColors(),
    )

    if (!healthOk) {
      Text(
        text = stringResource(R.string.openclaw_gateway_offline_connect_first),
        style = mobileCallout,
        color = clawberry.aiworm.cn.ui.mobileWarning,
      )
    }

    capturedPcm?.let { pcm ->
      VoiceClipBar(
        pcm = pcm,
        isTranscribing = voiceState == AsrVoiceState.Transcribing,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box {
        Surface(
          onClick = { showThinkingMenu = true },
          shape = RoundedCornerShape(14.dp),
          color = mobileCardSurface,
          border = BorderStroke(1.dp, mobileBorderStrong),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = thinkingLabel(thinkingLevel),
              style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
              color = mobileTextSecondary,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.openclaw_select_thinking_level), modifier = Modifier.size(18.dp), tint = mobileTextTertiary)
          }
        }

        DropdownMenu(
          expanded = showThinkingMenu,
          onDismissRequest = { showThinkingMenu = false },
          shape = RoundedCornerShape(16.dp),
          containerColor = mobileCardSurface,
          tonalElevation = 0.dp,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, mobileBorder),
        ) {
          ThinkingMenuItem("off", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("low", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("medium", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("high", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
        }
      }

      SecondaryActionButton(
        label = stringResource(R.string.common_attach),
        icon = Icons.Default.AttachFile,
        enabled = true,
        compact = true,
        onClick = onPickImages,
      )

      SecondaryActionButton(
        label = stringResource(R.string.openclaw_refresh),
        icon = Icons.Default.Refresh,
        enabled = true,
        compact = true,
        onClick = onRefresh,
      )

      SecondaryActionButton(
        label = stringResource(R.string.openclaw_abort),
        icon = Icons.Default.Stop,
        enabled = pendingRunCount > 0,
        compact = true,
        onClick = onAbort,
      )

      // ── Mic / Voice input button ──────────────────────────────────────────
      SecondaryActionButton(
        label = when (voiceState) {
          AsrVoiceState.Idle -> stringResource(R.string.asr_mic_start)
          AsrVoiceState.Recording -> stringResource(R.string.asr_recording)
          AsrVoiceState.Transcribing -> stringResource(R.string.asr_transcribing)
          AsrVoiceState.Failed -> stringResource(R.string.asr_failed)
        },
        icon = when (voiceState) {
          AsrVoiceState.Idle, AsrVoiceState.Failed -> Icons.Default.Mic
          AsrVoiceState.Recording -> Icons.Default.MicOff
          AsrVoiceState.Transcribing -> Icons.Default.Mic
        },
        containerColor = if (voiceState == AsrVoiceState.Recording) Color(0xFFE53935) else mobileCardSurface,
        iconTint = if (voiceState == AsrVoiceState.Recording) Color.White else mobileTextSecondary,
        enabled = voiceState != AsrVoiceState.Transcribing,
        compact = true,
        onClick = {
          when (voiceState) {
            AsrVoiceState.Idle -> {
              capturedPcm = null
              voiceRecorder.start()
              voiceState = AsrVoiceState.Recording
            }
            AsrVoiceState.Failed -> {
              val pcmToRetry = capturedPcm
              if (pcmToRetry != null) {
                // Re-send the same clip to ASR without re-recording
                voiceState = AsrVoiceState.Transcribing
                transcribeJob = scope.launch(Dispatchers.IO) {
                  val text = AsrClient.transcribe(asrUrl, pcmToRetry, asrMode)
                  withContext(Dispatchers.Main) {
                    if (!text.isNullOrBlank()) {
                      input = text
                      capturedPcm = null
                      voiceState = AsrVoiceState.Idle
                      android.widget.Toast.makeText(
                        context, context.getString(R.string.asr_success),
                        android.widget.Toast.LENGTH_SHORT
                      ).show()
                    } else {
                      voiceState = AsrVoiceState.Failed
                      android.widget.Toast.makeText(
                        context, context.getString(R.string.asr_failed_message),
                        android.widget.Toast.LENGTH_SHORT
                      ).show()
                    }
                  }
                }
              } else {
                voiceRecorder.start()
                voiceState = AsrVoiceState.Recording
              }
            }
            AsrVoiceState.Recording -> {
              voiceState = AsrVoiceState.Transcribing
              transcribeJob = scope.launch(Dispatchers.IO) {
                val pcm = voiceRecorder.stop()
                if (VoiceRecorder.isSilent(pcm)) {
                  withContext(Dispatchers.Main) {
                    voiceState = AsrVoiceState.Idle
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
                    voiceState = AsrVoiceState.Idle
                    android.widget.Toast.makeText(
                      context, context.getString(R.string.asr_success),
                      android.widget.Toast.LENGTH_SHORT
                    ).show()
                  } else {
                    voiceState = AsrVoiceState.Failed
                    android.widget.Toast.makeText(
                      context, context.getString(R.string.asr_failed_message),
                      android.widget.Toast.LENGTH_SHORT
                    ).show()
                  }
                }
              }
            }
            AsrVoiceState.Transcribing -> Unit
          }
        },
      )

      // ── ASR cancel button (visible while recording or transcribing) ──────
      if (voiceState == AsrVoiceState.Recording || voiceState == AsrVoiceState.Transcribing) {
        SecondaryActionButton(
          label = stringResource(R.string.asr_cancel),
          icon = Icons.Default.Close,
          enabled = true,
          containerColor = mobileCardSurface,
          iconTint = mobileTextSecondary,
          compact = true,
          onClick = {
            if (voiceState == AsrVoiceState.Recording) {
              voiceRecorder.release()  // stops mic and discards PCM — nothing sent to ASR
            } else {
              transcribeJob?.cancel()  // abort in-flight HTTP request
            }
            transcribeJob = null
            capturedPcm = null
            voiceState = AsrVoiceState.Idle
          },
        )
      }

      // ── ASR mode selector (tap to cycle: 2pass → offline → online) ──────
      Surface(
        onClick = {
          asrMode = when (asrMode) { "2pass" -> "offline"; "offline" -> "online"; else -> "2pass" }
        },
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorderStrong),
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

      Spacer(modifier = Modifier.weight(1f))

      Button(
        onClick = {
          val text = input
          input = ""
          onSend(text)
        },
        enabled = canSend,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = mobileAccent,
            contentColor = Color.White,
            disabledContainerColor = mobileBorderStrong,
            disabledContentColor = mobileTextTertiary,
          ),
        border = BorderStroke(1.dp, if (canSend) mobileAccentBorderStrong else mobileBorderStrong),
      ) {
        if (sendBusy) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.common_send),
          style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SecondaryActionButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  enabled: Boolean,
  compact: Boolean = false,
  containerColor: Color = mobileCardSurface,
  iconTint: Color = mobileTextSecondary,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = if (compact) Modifier.size(44.dp) else Modifier.height(44.dp),
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = iconTint,
        disabledContainerColor = mobileCardSurface,
        disabledContentColor = mobileTextTertiary,
      ),
    border = BorderStroke(1.dp, mobileBorderStrong),
    contentPadding = if (compact) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
  ) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = iconTint)
    if (!compact) {
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = label,
        style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) mobileTextSecondary else mobileTextTertiary,
      )
    }
  }
}

@Composable
private fun ThinkingMenuItem(
  value: String,
  current: String,
  onSet: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(thinkingLabel(value), style = mobileCallout, color = mobileText) },
    onClick = {
      onSet(value)
      onDismiss()
    },
    trailingIcon = {
      if (value == current.trim().lowercase()) {
        Text("✓", style = mobileCallout, color = mobileAccent)
      } else {
        Spacer(modifier = Modifier.width(10.dp))
      }
    },
  )
}

@Composable
private fun thinkingLabel(raw: String): String {
  return when (raw.trim().lowercase()) {
    "low" -> stringResource(R.string.openclaw_thinking_low)
    "medium" -> stringResource(R.string.openclaw_thinking_medium)
    "high" -> stringResource(R.string.openclaw_thinking_high)
    else -> stringResource(R.string.openclaw_thinking_off)
  }
}

@Composable
private fun AttachmentsStrip(
  attachments: List<PendingImageAttachment>,
  onRemoveAttachment: (id: String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (att in attachments) {
      AttachmentChip(
        fileName = att.fileName,
        onRemove = { onRemoveAttachment(att.id) },
      )
    }
  }
}

@Composable
private fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileBorderStrong),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = fileName,
        style = mobileCaption1,
        color = mobileText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        onClick = onRemove,
        shape = RoundedCornerShape(999.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorderStrong),
      ) {
        Text(
          text = "×",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
          color = mobileTextSecondary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun chatTextFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    focusedBorderColor = mobileAccent,
    unfocusedBorderColor = mobileBorder,
    focusedTextColor = mobileText,
    unfocusedTextColor = mobileText,
    cursorColor = mobileAccent,
  )

@Composable
private fun mobileBodyStyle() =
  MaterialTheme.typography.bodyMedium.copy(
    fontFamily = clawberry.aiworm.cn.ui.mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  )
