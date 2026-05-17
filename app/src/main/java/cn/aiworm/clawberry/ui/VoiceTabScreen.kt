package clawberry.aiworm.cn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import clawberry.aiworm.cn.MainViewModel
import clawberry.aiworm.cn.R
import clawberry.aiworm.cn.voice.VoiceConversationEntry
import clawberry.aiworm.cn.voice.VoiceConversationRole
import kotlin.math.max

@Composable
fun VoiceTabScreen(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = remember(context) { context.findActivity() }
  val listState = rememberLazyListState()

  val gatewayStatus by viewModel.statusText.collectAsState()
  val micEnabled by viewModel.micEnabled.collectAsState()
  val micCooldown by viewModel.micCooldown.collectAsState()
  val speakerEnabled by viewModel.speakerEnabled.collectAsState()
  val micStatusText by viewModel.micStatusText.collectAsState()
  val micLiveTranscript by viewModel.micLiveTranscript.collectAsState()
  val micConversation by viewModel.micConversation.collectAsState()
  val micInputLevel by viewModel.micInputLevel.collectAsState()
  val micIsSending by viewModel.micIsSending.collectAsState()
  val useCustomAsr by viewModel.useCustomAsr.collectAsState()
  val useIdentityAsr by viewModel.useIdentityAsr.collectAsState()
  val kwsEnabled by viewModel.kwsEnabled.collectAsState()
  val asrUrl by viewModel.asrUrl.collectAsState()
  val voicePrintRegistered by clawberry.aiworm.cn.voice.SpeakerRegistrationManager.globalIsRegistered.collectAsState()

  // Animate the ring level here at the top composable scope for stable recomposition.
  val animatedRingLevel by animateFloatAsState(
    targetValue = if (micEnabled) micInputLevel.coerceIn(0f, 1f) else 0f,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "micRingLevel",
  )

  val hasStreamingAssistant = micConversation.any { it.role == VoiceConversationRole.Assistant && it.isStreaming }
  val showThinkingBubble = micIsSending && !hasStreamingAssistant

  var hasMicPermission by remember { mutableStateOf(context.hasRecordAudioPermission()) }
  var pendingMicEnable by remember { mutableStateOf(false) }

  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasMicPermission = context.hasRecordAudioPermission()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // Stop TTS when leaving the voice screen
      viewModel.setVoiceScreenActive(false)
    }
  }

  val requestMicPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasMicPermission = granted
      if (granted && pendingMicEnable) {
        viewModel.setMicEnabled(true)
      }
      pendingMicEnable = false
    }

  LaunchedEffect(micConversation.size, showThinkingBubble) {
    val total = micConversation.size + if (showThinkingBubble) 1 else 0
    if (total > 0) {
      listState.animateScrollToItem(total - 1)
    }
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient)
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().weight(1f),
      contentPadding = PaddingValues(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (micConversation.isEmpty() && !showThinkingBubble) {
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

      items(items = micConversation, key = { it.id }) { entry ->
        VoiceTurnBubble(entry = entry)
      }

      if (showThinkingBubble) {
        item {
          VoiceThinkingBubble()
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (!micLiveTranscript.isNullOrBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = mobileAccentSoft,
          border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.2f)),
        ) {
          Text(
            micLiveTranscript!!.trim(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = mobileCallout,
            color = mobileText,
          )
        }
      }

      // Mic button with input-reactive ring + speaker toggle
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Speaker toggle
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(
            onClick = { viewModel.setSpeakerEnabled(!speakerEnabled) },
            modifier = Modifier.size(48.dp),
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = if (speakerEnabled) mobileSurface else mobileDangerSoft,
              ),
          ) {
            Icon(
              imageVector = if (speakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
              contentDescription = if (speakerEnabled) stringResource(R.string.openclaw_mute_speaker) else stringResource(R.string.openclaw_unmute_speaker),
              modifier = Modifier.size(22.dp),
              tint = if (speakerEnabled) mobileTextSecondary else mobileDanger,
            )
          }
          Text(
            if (speakerEnabled) stringResource(R.string.openclaw_speaker_label) else stringResource(R.string.openclaw_speaker_muted),
            style = mobileCaption2,
            color = if (speakerEnabled) mobileTextTertiary else mobileDanger,
          )
        }

        // Ring size = 68dp base + up to 22dp driven by mic input level.
        // The outer Box is fixed at 90dp (max ring size) so the ring never shifts the button.
        Box(
          modifier = Modifier.padding(horizontal = 16.dp).size(90.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (micEnabled || animatedRingLevel > 0.01f) {
            val effectiveLevel = max(animatedRingLevel, if (micEnabled) 0.05f else 0f)
            val ringSize = 68.dp + (22.dp * effectiveLevel)
            Box(
              modifier =
                Modifier
                  .size(ringSize)
                  .background(mobileAccent.copy(alpha = 0.12f + 0.14f * effectiveLevel), CircleShape),
            )
          }
          Button(
            onClick = {
              if (micCooldown) return@Button
              if (micEnabled) {
                viewModel.setMicEnabled(false)
                return@Button
              }
              if (hasMicPermission) {
                viewModel.setMicEnabled(true)
              } else {
                pendingMicEnable = true
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
              }
            },
            enabled = !micCooldown,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(60.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = if (micCooldown) mobileTextSecondary else if (micEnabled) mobileDanger else mobileAccent,
                contentColor = Color.White,
                disabledContainerColor = mobileTextSecondary,
                disabledContentColor = Color.White.copy(alpha = 0.5f),
              ),
          ) {
            Icon(
              imageVector = if (micEnabled) Icons.Default.MicOff else Icons.Default.Mic,
              contentDescription = if (micEnabled) stringResource(R.string.openclaw_mic_turn_off) else stringResource(R.string.openclaw_mic_turn_on),
              modifier = Modifier.size(24.dp),
            )
          }
        }

        // ASR backend toggle — compact vertical stack beside the mic button
        val funAsrAvailable = asrUrl.isNotBlank()
        val builtInActive = !useCustomAsr && !useIdentityAsr
        var asrExpanded by remember { mutableStateOf(false) }
        // Derived label for the currently active mode (shown when collapsed)
        val activeAsrLabel = when {
          kwsEnabled -> "唤醒词"
          useIdentityAsr -> if (voicePrintRegistered) "FunASR-ID" else "FunASR-ID !"
          useCustomAsr -> if (funAsrAvailable) "FunASR" else "FunASR !"
          else -> "Built-in"
        }
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
              // Collapsed: only the active chip
              // Expanded: all chips
              AnimatedVisibility(
                visible = !asrExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
              ) {
                // Active-mode summary chip (tapping the outer Surface toggles expand)
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
                  // Built-in chip
                  Surface(
                    onClick = { viewModel.setUseCustomAsr(false); asrExpanded = false },
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
                  // FunASR chip
                  Surface(
                    onClick = { viewModel.setUseCustomAsr(true); asrExpanded = false },
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
                  // FunASR-ID chip
                  Surface(
                    onClick = { if (voicePrintRegistered) { viewModel.setUseIdentityAsr(true); asrExpanded = false } },
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
                  // KWS wake-word chip
                  Surface(
                    onClick = { viewModel.setKwsEnabled(!kwsEnabled); asrExpanded = false },
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

      // Status + labels
      val stateColor =
        when {
          micEnabled -> mobileSuccess
          micIsSending -> mobileAccent
          else -> mobileTextSecondary
        }
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (micEnabled) mobileSuccessSoft else mobileSurface,
        border = BorderStroke(1.dp, if (micEnabled) mobileSuccess.copy(alpha = 0.3f) else mobileBorder),
      ) {
        Text(
          "$gatewayStatus · $micStatusText",
          style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
          color = stateColor,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
      }

      if (!hasMicPermission) {
        val showRationale =
          if (activity == null) {
            false
          } else {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
          }
        Text(
          if (showRationale) {
            stringResource(R.string.openclaw_voice_permission_required)
          } else {
            stringResource(R.string.openclaw_voice_permission_blocked)
          },
          style = mobileCaption1,
          color = mobileWarning,
          textAlign = TextAlign.Center,
        )
        Button(
          onClick = { openAppSettings(context) },
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = mobileSurfaceStrong, contentColor = mobileText),
        ) {
          Text(stringResource(R.string.common_open_settings), style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }
      }
    }
  }
}

@Composable
private fun VoiceTurnBubble(entry: VoiceConversationEntry) {
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
          if (isUser) stringResource(R.string.openclaw_you) else "OpenClaw",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (isUser) mobileAccent else mobileTextSecondary,
        )
        Text(
          if (entry.isStreaming && entry.text.isBlank()) stringResource(R.string.openclaw_listening_response) else entry.text,
          style = mobileCallout,
          color = mobileText,
        )
      }
    }
  }
}

@Composable
private fun VoiceThinkingBubble() {
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
        ThinkingDots(color = mobileTextSecondary)
        Text(stringResource(R.string.openclaw_thinking), style = mobileCallout, color = mobileTextSecondary)
      }
    }
  }
}

@Composable
private fun ThinkingDots(color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    ThinkingDot(alpha = 0.38f, color = color)
    ThinkingDot(alpha = 0.62f, color = color)
    ThinkingDot(alpha = 0.90f, color = color)
  }
}

@Composable
private fun ThinkingDot(alpha: Float, color: Color) {
  Surface(
    modifier = Modifier.size(6.dp).alpha(alpha),
    shape = CircleShape,
    color = color,
  ) {}
}

private fun Context.hasRecordAudioPermission(): Boolean {
  return (
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    )
}

private fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
