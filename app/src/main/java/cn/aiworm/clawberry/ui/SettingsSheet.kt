package clawberry.aiworm.cn.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import clawberry.aiworm.cn.BuildConfig
import clawberry.aiworm.cn.AppLanguage
import clawberry.aiworm.cn.LocationMode
import clawberry.aiworm.cn.MainViewModel
import clawberry.aiworm.cn.R
import clawberry.aiworm.cn.node.DeviceNotificationListenerService
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import clawberry.aiworm.cn.voice.KwsTtsPlayer
import clawberry.aiworm.cn.voice.TtsModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun SettingsSheet(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val instanceId by viewModel.instanceId.collectAsState()
  val displayName by viewModel.displayName.collectAsState()
  val cameraEnabled by viewModel.cameraEnabled.collectAsState()
  val locationMode by viewModel.locationMode.collectAsState()
  val locationPreciseEnabled by viewModel.locationPreciseEnabled.collectAsState()
  val preventSleep by viewModel.preventSleep.collectAsState()
  val canvasDebugStatusEnabled by viewModel.canvasDebugStatusEnabled.collectAsState()
  val floatingOverlayEnabled by viewModel.floatingOverlayEnabled.collectAsState()
  val appLanguage by viewModel.appLanguage.collectAsState()
  val asrUrl by viewModel.asrUrl.collectAsState()
  var asrUrlDraft by rememberSaveable(asrUrl) { mutableStateOf(asrUrl) }
  val kwsGreeting by viewModel.kwsGreeting.collectAsState()
  var kwsGreetingDraft by rememberSaveable(kwsGreeting) { mutableStateOf(kwsGreeting) }
  val kwsTitle by viewModel.kwsTitle.collectAsState()
  var kwsTitleDraft by rememberSaveable(kwsTitle) { mutableStateOf(kwsTitle) }
  val kwsRetryPhrase by viewModel.kwsRetryPhrase.collectAsState()
  var kwsRetryPhraseDraft by rememberSaveable(kwsRetryPhrase) { mutableStateOf(kwsRetryPhrase) }
  val kwsSuccessPhrase by viewModel.kwsSuccessPhrase.collectAsState()
  var kwsSuccessPhraseDraft by rememberSaveable(kwsSuccessPhrase) { mutableStateOf(kwsSuccessPhrase) }
  val kwsAckPhrase by viewModel.kwsAckPhrase.collectAsState()
  var kwsAckPhraseDraft by rememberSaveable(kwsAckPhrase) { mutableStateOf(kwsAckPhrase) }
  val voiceThinkingPhrase by viewModel.voiceThinkingPhrase.collectAsState()
  val voiceThinkingEnabled by viewModel.voiceThinkingEnabled.collectAsState()
  var voiceThinkingPhraseDraft by rememberSaveable(voiceThinkingPhrase) { mutableStateOf(voiceThinkingPhrase) }
  val voiceToolCallsPhrase by viewModel.voiceToolCallsPhrase.collectAsState()
  val voiceToolCallsEnabled by viewModel.voiceToolCallsEnabled.collectAsState()
  var voiceToolCallsPhraseDraft by rememberSaveable(voiceToolCallsPhrase) { mutableStateOf(voiceToolCallsPhrase) }
  val voiceTtsHint by viewModel.voiceTtsHint.collectAsState()
  var voiceTtsHintDraft by rememberSaveable(voiceTtsHint) { mutableStateOf(voiceTtsHint) }
  val identityAsrThreshold by viewModel.identityAsrThreshold.collectAsState()
  val ttsModel by viewModel.ttsModel.collectAsState()
  val ttsSpeakerId by viewModel.ttsSpeakerId.collectAsState()
  var ttsSpeakerIdDraft by rememberSaveable(ttsSpeakerId) { mutableStateOf(ttsSpeakerId.toString()) }

  // Self-contained TTS player for the greeting preview — works even before NodeRuntime starts
  val greetingPreviewScope = rememberCoroutineScope()
  val greetingPreviewPlayer = remember(context, ttsModel, ttsSpeakerId) {
    KwsTtsPlayer(context, greetingPreviewScope, ttsModel, ttsSpeakerId).also { it.init() }
  }
  DisposableEffect(greetingPreviewPlayer) { onDispose { greetingPreviewPlayer.release() } }

  val listState = rememberLazyListState()
  val deviceModel =
    remember {
      listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifEmpty { "Android" }
    }
  val appVersion =
    remember {
      val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
      if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
        "$versionName-dev"
      } else {
        versionName
      }
    }
  val listItemColors =
    ListItemDefaults.colors(
      containerColor = Color.Transparent,
      headlineColor = mobileText,
      supportingColor = mobileTextSecondary,
      trailingIconColor = mobileTextSecondary,
      leadingIconColor = mobileTextSecondary,
    )

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val cameraOk = perms[Manifest.permission.CAMERA] == true
      viewModel.setCameraEnabled(cameraOk)
    }

  var pendingLocationRequest by remember { mutableStateOf(false) }
  var pendingPreciseToggle by remember { mutableStateOf(false) }

  val locationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val fineOk = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
      val coarseOk = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
      val granted = fineOk || coarseOk

      if (pendingPreciseToggle) {
        pendingPreciseToggle = false
        viewModel.setLocationPreciseEnabled(fineOk)
        return@rememberLauncherForActivityResult
      }

      if (pendingLocationRequest) {
        pendingLocationRequest = false
        viewModel.setLocationMode(if (granted) LocationMode.WhileUsing else LocationMode.Off)
      }
    }

  var micPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val audioPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      micPermissionGranted = granted
    }

  val smsPermissionAvailable =
    remember {
      context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) == true
    }
  val photosPermission =
    if (Build.VERSION.SDK_INT >= 33) {
      Manifest.permission.READ_MEDIA_IMAGES
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }
  val motionPermissionRequired = true
  val motionAvailable = remember(context) { hasMotionCapabilities(context) }

  var notificationsPermissionGranted by
    remember {
      mutableStateOf(hasNotificationsPermission(context))
    }
  val notificationsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      notificationsPermissionGranted = granted
    }

  var notificationListenerEnabled by
    remember {
      mutableStateOf(isNotificationListenerEnabled(context))
    }

  var overlayPermissionGranted by
    remember {
      mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
  var photosPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, photosPermission) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val photosPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      photosPermissionGranted = granted
    }

  var contactsPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val contactsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val readOk = perms[Manifest.permission.READ_CONTACTS] == true
      val writeOk = perms[Manifest.permission.WRITE_CONTACTS] == true
      contactsPermissionGranted = readOk && writeOk
    }

  var calendarPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val calendarPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val readOk = perms[Manifest.permission.READ_CALENDAR] == true
      val writeOk = perms[Manifest.permission.WRITE_CALENDAR] == true
      calendarPermissionGranted = readOk && writeOk
    }

  var callLogPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val callLogPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      callLogPermissionGranted = granted
    }

  var motionPermissionGranted by
    remember {
      mutableStateOf(
        !motionPermissionRequired ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val motionPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      motionPermissionGranted = granted
    }

  var smsPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val smsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val sendOk = perms[Manifest.permission.SEND_SMS] == true
      val readOk = perms[Manifest.permission.READ_SMS] == true
      smsPermissionGranted = sendOk && readOk
      viewModel.refreshGatewayConnection()
    }

  // ── ClawBoard state ──
  var clawboardIp by rememberSaveable { mutableStateOf("") }
  var clawboardPort by rememberSaveable { mutableStateOf("80") }

  val clawboardQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
    val scanned = result.contents ?: return@rememberLauncherForActivityResult
    if (scanned.startsWith("http://") || scanned.startsWith("https://")) {
      runCatching {
        val uri = Uri.parse(scanned)
        clawboardIp = uri.host ?: ""
        clawboardPort = if ((uri.port) > 0) uri.port.toString() else "80"
      }
    } else {
      val colonIdx = scanned.lastIndexOf(':')
      if (colonIdx > 0) {
        clawboardIp = scanned.substring(0, colonIdx).trim()
        clawboardPort = scanned.substring(colonIdx + 1).trim()
      } else {
        clawboardIp = scanned.trim()
      }
    }
  }

  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          micPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
              PackageManager.PERMISSION_GRANTED
          notificationsPermissionGranted = hasNotificationsPermission(context)
          notificationListenerEnabled = isNotificationListenerEnabled(context)
          photosPermissionGranted =
            ContextCompat.checkSelfPermission(context, photosPermission) ==
              PackageManager.PERMISSION_GRANTED
          contactsPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
              PackageManager.PERMISSION_GRANTED &&
              ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
              PackageManager.PERMISSION_GRANTED
          calendarPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
              PackageManager.PERMISSION_GRANTED &&
              ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
              PackageManager.PERMISSION_GRANTED
          callLogPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
              PackageManager.PERMISSION_GRANTED
          motionPermissionGranted =
            !motionPermissionRequired ||
              ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
              PackageManager.PERMISSION_GRANTED
          smsPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
              PackageManager.PERMISSION_GRANTED &&
              ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
              PackageManager.PERMISSION_GRANTED
          overlayPermissionGranted = android.provider.Settings.canDrawOverlays(context)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun setCameraEnabledChecked(checked: Boolean) {
    if (!checked) {
      viewModel.setCameraEnabled(false)
      return
    }

    val cameraOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    if (cameraOk) {
      viewModel.setCameraEnabled(true)
    } else {
      permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
  }

  fun requestLocationPermissions() {
    val fineOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarseOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (fineOk || coarseOk) {
      viewModel.setLocationMode(LocationMode.WhileUsing)
    } else {
      pendingLocationRequest = true
      locationPermissionLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
      )
    }
  }

  fun setPreciseLocationChecked(checked: Boolean) {
    if (!checked) {
      viewModel.setLocationPreciseEnabled(false)
      return
    }
    val fineOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (fineOk) {
      viewModel.setLocationPreciseEnabled(true)
    } else {
      pendingPreciseToggle = true
      locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient),
  ) {
    LazyColumn(
      state = listState,
      modifier =
        Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .imePadding()
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // ── ClawBoard ──
      item {
        Text(
          stringResource(R.string.settings_clawboard_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          OutlinedTextField(
            value = clawboardIp,
            onValueChange = { clawboardIp = it },
            label = { Text(stringResource(R.string.settings_clawboard_ip), style = mobileCaption1, color = mobileTextSecondary) },
            placeholder = { Text("192.168.1.1", style = mobileBody, color = mobileTextTertiary) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = mobileBody.copy(color = mobileText),
            singleLine = true,
            colors = settingsTextFieldColors(),
          )
          HorizontalDivider(color = mobileBorder)
          OutlinedTextField(
            value = clawboardPort,
            onValueChange = { clawboardPort = it },
            label = { Text(stringResource(R.string.settings_clawboard_port), style = mobileCaption1, color = mobileTextSecondary) },
            placeholder = { Text("80", style = mobileBody, color = mobileTextTertiary) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = mobileBody.copy(color = mobileText),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = settingsTextFieldColors(),
          )
          HorizontalDivider(color = mobileBorder)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = {
                val ip = clawboardIp.trim()
                val port = clawboardPort.trim()
                val url = if (port.isEmpty() || port == "80") "http://$ip" else "http://$ip:$port"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
              },
              enabled = clawboardIp.isNotBlank(),
              colors = settingsPrimaryButtonColors(),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text(stringResource(R.string.settings_clawboard_open), style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }
            OutlinedButton(
              onClick = {
                clawboardQrLauncher.launch(
                  ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Scan ClawBoard QR code")
                    setBeepEnabled(false)
                    setCaptureActivity(clawberry.aiworm.cn.PortraitCaptureActivity::class.java)
                  }
                )
              },
              shape = RoundedCornerShape(14.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(stringResource(R.string.settings_clawboard_scan_qr), style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }
          }
        }
      }

      // ── Language ──
      item {
        Text(
          stringResource(R.string.settings_language_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_language_english), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_language_english_subtitle), style = mobileCallout) },
            trailingContent = {
              RadioButton(
                selected = appLanguage == AppLanguage.English,
                onClick = { viewModel.setAppLanguage(AppLanguage.English) },
              )
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_language_chinese), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_language_chinese_subtitle), style = mobileCallout) },
            trailingContent = {
              RadioButton(
                selected = appLanguage == AppLanguage.ChineseSimplified,
                onClick = { viewModel.setAppLanguage(AppLanguage.ChineseSimplified) },
              )
            },
          )
        }
      }

      // ── Network Discovery ──
      item {
        Text(
          stringResource(R.string.settings_network_discovery_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        FindClawGatewayCard(
          onGatewaySelected = { selectedHost, selectedPort, _ ->
            viewModel.setManualHost(selectedHost)
            viewModel.setManualPort(selectedPort)
            viewModel.setManualEnabled(true)
          },
        )
      }

      // ── Node ──
      item {
        Text(
          stringResource(R.string.settings_device_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          OutlinedTextField(
            value = displayName,
            onValueChange = viewModel::setDisplayName,
            label = { Text(stringResource(R.string.settings_name_label), style = mobileCaption1, color = mobileTextSecondary) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = mobileBody.copy(color = mobileText),
            colors = settingsTextFieldColors(),
          )
          HorizontalDivider(color = mobileBorder)
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text("$deviceModel · $appVersion", style = mobileCallout, color = mobileTextSecondary)
            Text(
              instanceId.take(8) + "…",
              style = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
              color = mobileTextTertiary,
            )
          }
        }
      }

      // ── Media ──
      item {
        Text(
          stringResource(R.string.settings_media_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_media_mic_title), style = mobileHeadline) },
            supportingContent = {
              Text(
                if (micPermissionGranted) stringResource(R.string.settings_media_mic_granted) else stringResource(R.string.settings_media_mic_subtitle),
                style = mobileCallout,
              )
            },
            trailingContent = {
              Button(
                onClick = {
                  if (micPermissionGranted) {
                    openAppSettings(context)
                  } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (micPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_media_camera_title), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_media_camera_subtitle), style = mobileCallout) },
            trailingContent = { Switch(checked = cameraEnabled, onCheckedChange = ::setCameraEnabledChecked) },
          )
        }
      }

      // ── Notifications & Messaging ──
      item {
        Text(
          stringResource(R.string.settings_notifications_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_notif_system_title), style = mobileHeadline) },
            supportingContent = {
              Text(stringResource(R.string.settings_notif_system_subtitle), style = mobileCallout)
            },
            trailingContent = {
              Button(
                onClick = {
                  if (notificationsPermissionGranted || Build.VERSION.SDK_INT < 33) {
                    openAppSettings(context)
                  } else {
                    notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (notificationsPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_notif_listener_title), style = mobileHeadline) },
            supportingContent = {
              Text(stringResource(R.string.settings_notif_listener_subtitle), style = mobileCallout)
            },
            trailingContent = {
              Button(
                onClick = { openNotificationListenerSettings(context) },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (notificationListenerEnabled) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_enable),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          if (smsPermissionAvailable) {
            HorizontalDivider(color = mobileBorder)
            ListItem(
              modifier = Modifier.fillMaxWidth(),
              colors = listItemColors,
              headlineContent = { Text(stringResource(R.string.settings_notif_sms_title), style = mobileHeadline) },
              supportingContent = {
                Text(stringResource(R.string.settings_notif_sms_subtitle), style = mobileCallout)
              },
              trailingContent = {
                Button(
                  onClick = {
                    if (smsPermissionGranted) {
                      openAppSettings(context)
                    } else {
                      smsPermissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS))
                    }
                  },
                  colors = settingsPrimaryButtonColors(),
                  shape = RoundedCornerShape(14.dp),
                ) {
                  Text(
                    if (smsPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                    style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                  )
                }
              },
            )
          }
        }
      }

      // ── Data Access ──
      item {
        Text(
          stringResource(R.string.settings_data_access_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_data_photos_title), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_data_photos_subtitle), style = mobileCallout) },
            trailingContent = {
              Button(
                onClick = {
                  if (photosPermissionGranted) {
                    openAppSettings(context)
                  } else {
                    photosPermissionLauncher.launch(photosPermission)
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (photosPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_data_contacts_title), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_data_contacts_subtitle), style = mobileCallout) },
            trailingContent = {
              Button(
                onClick = {
                  if (contactsPermissionGranted) {
                    openAppSettings(context)
                  } else {
                    contactsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (contactsPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_data_calendar_title), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_data_calendar_subtitle), style = mobileCallout) },
            trailingContent = {
              Button(
                onClick = {
                  if (calendarPermissionGranted) {
                    openAppSettings(context)
                  } else {
                    calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (calendarPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_data_calllog_title), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_data_calllog_subtitle), style = mobileCallout) },
            trailingContent = {
              Button(
                onClick = {
                  if (callLogPermissionGranted) {
                    openAppSettings(context)
                  } else {
                    callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                  }
                },
                colors = settingsPrimaryButtonColors(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text(
                  if (callLogPermissionGranted) stringResource(R.string.settings_action_manage) else stringResource(R.string.settings_action_grant),
                  style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                )
              }
            },
          )
          if (motionAvailable) {
            HorizontalDivider(color = mobileBorder)
            ListItem(
              modifier = Modifier.fillMaxWidth(),
              colors = listItemColors,
              headlineContent = { Text(stringResource(R.string.settings_data_motion_title), style = mobileHeadline) },
              supportingContent = { Text(stringResource(R.string.settings_data_motion_subtitle), style = mobileCallout) },
              trailingContent = {
                val motionButtonLabel =
                  when {
                    !motionPermissionRequired -> stringResource(R.string.settings_action_manage)
                    motionPermissionGranted -> stringResource(R.string.settings_action_manage)
                    else -> stringResource(R.string.settings_action_grant)
                  }
                Button(
                  onClick = {
                    if (!motionPermissionRequired || motionPermissionGranted) {
                      openAppSettings(context)
                    } else {
                      motionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                  },
                  colors = settingsPrimaryButtonColors(),
                  shape = RoundedCornerShape(14.dp),
                ) {
                  Text(motionButtonLabel, style = mobileCallout.copy(fontWeight = FontWeight.Bold))
                }
              },
            )
          }
        }
      }

      // ── Location ──
      item {
        Text(
          stringResource(R.string.settings_location_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_location_off), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_location_off_subtitle), style = mobileCallout) },
            trailingContent = {
              RadioButton(
                selected = locationMode == LocationMode.Off,
                onClick = { viewModel.setLocationMode(LocationMode.Off) },
              )
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_location_while_using), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_location_while_using_subtitle), style = mobileCallout) },
            trailingContent = {
              RadioButton(
                selected = locationMode == LocationMode.WhileUsing,
                onClick = { requestLocationPermissions() },
              )
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_location_precise), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_location_precise_subtitle), style = mobileCallout) },
            trailingContent = {
              Switch(
                checked = locationPreciseEnabled,
                onCheckedChange = ::setPreciseLocationChecked,
                enabled = locationMode != LocationMode.Off,
              )
            },
          )
        }
      }

      // ── Preferences ──
      item {
        Text(
          stringResource(R.string.settings_preferences_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_pref_prevent_sleep), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_pref_prevent_sleep_subtitle), style = mobileCallout) },
            trailingContent = { Switch(checked = preventSleep, onCheckedChange = viewModel::setPreventSleep) },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_pref_debug_canvas), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_pref_debug_canvas_subtitle), style = mobileCallout) },
            trailingContent = {
              Switch(
                checked = canvasDebugStatusEnabled,
                onCheckedChange = viewModel::setCanvasDebugStatusEnabled,
              )
            },
          )
          HorizontalDivider(color = mobileBorder)
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_pref_floating_overlay), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_pref_floating_overlay_subtitle), style = mobileCallout) },
            trailingContent = {
              if (!overlayPermissionGranted) {
                Button(
                  onClick = {
                    context.startActivity(
                      Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                      ),
                    )
                  },
                  colors = settingsPrimaryButtonColors(),
                  shape = RoundedCornerShape(14.dp),
                ) {
                  Text(stringResource(R.string.settings_pref_floating_overlay_grant), style = mobileCallout.copy(fontWeight = FontWeight.Bold))
                }
              } else {
                Switch(
                  checked = floatingOverlayEnabled,
                  onCheckedChange = { checked ->
                    viewModel.setFloatingOverlayEnabled(checked)
                    if (!checked) clawberry.aiworm.cn.FloatingOverlayService.stop(context)
                  },
                )
              }
            },
          )
        }
      }

      // ── ASR / Voice Input ──
      item {
        Text(
          stringResource(R.string.settings_asr_title),
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = { Text(stringResource(R.string.settings_asr_url_label), style = mobileHeadline) },
            supportingContent = { Text(stringResource(R.string.settings_asr_url_subtitle), style = mobileCallout) },
          )
          OutlinedTextField(
            value = asrUrlDraft,
            onValueChange = { asrUrlDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("wss://asr.aiworm.cn:443", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
              if (asrUrlDraft.trim() != asrUrl) {
                Button(
                  onClick = { viewModel.setAsrUrl(asrUrlDraft) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
        }
      }

      // ── TTS Model ──
      item {
        Text(
          if (appLanguage == AppLanguage.ChineseSimplified) "TTS 语音模型" else "TTS MODEL",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "语音合成模型" else "TTS Engine",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "选择用于关键词唤醒提示音的 TTS 模型" else "Model used for KWS voice prompts",
                style = mobileCallout,
              )
            },
          )
          Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
            TtsModel.entries.forEach { model ->
              Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { viewModel.setTtsModel(model) }
                  .padding(vertical = 4.dp),
              ) {
                RadioButton(
                  selected = ttsModel == model,
                  onClick = { viewModel.setTtsModel(model) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                  Text(model.displayName, style = mobileHeadline)
                  Text(
                    if (appLanguage == AppLanguage.ChineseSimplified)
                      if (model.maxSpeakers > 1) "${model.maxSpeakers} 个声线 (0–${model.maxSpeakers - 1})" else "1 个声线"
                    else
                      if (model.maxSpeakers > 1) "${model.maxSpeakers} voices (0–${model.maxSpeakers - 1})" else "1 voice",
                    style = mobileCallout,
                    color = mobileTextTertiary,
                  )
                }
              }
            }
          }
          // Speaker ID — only shown for multi-speaker models
          if (ttsModel.maxSpeakers > 1) {
            ListItem(
              modifier = Modifier.fillMaxWidth(),
              colors = listItemColors,
              headlineContent = {
                Text(
                  if (appLanguage == AppLanguage.ChineseSimplified) "声线 ID" else "Speaker ID",
                  style = mobileHeadline,
                )
              },
              supportingContent = {
                Text(
                  if (appLanguage == AppLanguage.ChineseSimplified)
                    "0 到 ${ttsModel.maxSpeakers - 1} 之间的整数"
                  else
                    "Integer between 0 and ${ttsModel.maxSpeakers - 1}",
                  style = mobileCallout,
                )
              },
            )
            OutlinedTextField(
              value = ttsSpeakerIdDraft,
              onValueChange = { ttsSpeakerIdDraft = it.filter { c -> c.isDigit() } },
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
              singleLine = true,
              placeholder = { Text("0", style = mobileCallout, color = mobileTextTertiary) },
              colors = settingsTextFieldColors(),
              textStyle = mobileCaption1.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              trailingIcon = {
                val parsed = ttsSpeakerIdDraft.toIntOrNull()
                val valid = parsed != null && parsed in 0 until ttsModel.maxSpeakers
                if (valid && parsed != ttsSpeakerId) {
                  Button(
                    onClick = { viewModel.setTtsSpeakerId(parsed!!) },
                    modifier = Modifier.padding(end = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = settingsPrimaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                  ) {
                    Text(stringResource(R.string.common_save), style = mobileCallout)
                  }
                }
              },
            )
          }
        }
      }

      // ── Voice Print ──
      item {
        Text(
          if (appLanguage == AppLanguage.ChineseSimplified) "语音提示" else "VOICE PROMPTS",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        Column(modifier = Modifier.settingsRowModifier()) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "欢迎词" else "Greeting",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "检测到唤醒词时播放的语音" else "Spoken when wake word is detected",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = kwsGreetingDraft,
            onValueChange = { kwsGreetingDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("你好主人", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (kwsGreetingDraft.trim() != kwsGreeting) {
                Button(
                  onClick = { viewModel.setKwsGreeting(kwsGreetingDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = {
                val text = kwsGreetingDraft.trim().ifEmpty { kwsGreeting }
                greetingPreviewPlayer.playText(text)
              },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
          HorizontalDivider(color = mobileBorder)
          // ── 称呼 (title / honorific) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "称呼" else "Title",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "用于所有语音播报中的称呼，如\u201c主人\u201d" else "Honorific used in all voice announcements",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = kwsTitleDraft,
            onValueChange = { kwsTitleDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("主人", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (kwsTitleDraft.trim() != kwsTitle) {
                Button(
                  onClick = { viewModel.setKwsTitle(kwsTitleDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          HorizontalDivider(color = mobileBorder)
          // ── 执行确认 (command acknowledgment phrase) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "执行确认" else "Command Acknowledgment",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "收到语音指令后的回应（称呼和指令内容会自动加在前后）" else "Spoken after receiving a command (title prepended, command text appended)",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = kwsAckPhraseDraft,
            onValueChange = { kwsAckPhraseDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("我马上执行您的命令", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (kwsAckPhraseDraft.trim() != kwsAckPhrase) {
                Button(
                  onClick = { viewModel.setKwsAckPhrase(kwsAckPhraseDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = {
                val title = kwsTitleDraft.trim().ifEmpty { kwsTitle }
                val body = kwsAckPhraseDraft.trim().ifEmpty { kwsAckPhrase }
                greetingPreviewPlayer.playText("$title，$body：示例指令")
              },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
          HorizontalDivider(color = mobileBorder)
          // ── 解析不清楚 (retry phrase) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "解析不清楚" else "Retry Prompt",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "语音识别失败时播放的提示（称呼会自动加在前面）" else "Played when ASR returns no result (title prepended)",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = kwsRetryPhraseDraft,
            onValueChange = { kwsRetryPhraseDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("请再说一遍你的指令", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (kwsRetryPhraseDraft.trim() != kwsRetryPhrase) {
                Button(
                  onClick = { viewModel.setKwsRetryPhrase(kwsRetryPhraseDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = {
                val title = kwsTitleDraft.trim().ifEmpty { kwsTitle }
                val body = kwsRetryPhraseDraft.trim().ifEmpty { kwsRetryPhrase }
                greetingPreviewPlayer.playText("$title，$body")
              },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
          HorizontalDivider(color = mobileBorder)
          // ── 执行成功 (success phrase) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "执行成功" else "Success Prompt",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "指令运行完毕时播放的提示（称呼会自动加在前面）" else "Played when agent run completes (title prepended)",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = kwsSuccessPhraseDraft,
            onValueChange = { kwsSuccessPhraseDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("你的指令运行完毕", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (kwsSuccessPhraseDraft.trim() != kwsSuccessPhrase) {
                Button(
                  onClick = { viewModel.setKwsSuccessPhrase(kwsSuccessPhraseDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = {
                val title = kwsTitleDraft.trim().ifEmpty { kwsTitle }
                val body = kwsSuccessPhraseDraft.trim().ifEmpty { kwsSuccessPhrase }
                greetingPreviewPlayer.playText("$title，$body")
              },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
          HorizontalDivider(color = mobileBorder)
          // ── thinking prompt (agent) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "思考提示" else "Thinking Prompt",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "代理思考时播放的语音" else "Played while the agent is thinking",
                style = mobileCallout,
              )
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              if (appLanguage == AppLanguage.ChineseSimplified) "启用该提示音" else "Enable this prompt",
              style = mobileCallout,
              color = mobileTextSecondary,
            )
            Switch(
              checked = voiceThinkingEnabled,
              onCheckedChange = { viewModel.setVoiceThinkingEnabled(it) },
            )
          }
          OutlinedTextField(
            value = voiceThinkingPhraseDraft,
            onValueChange = { voiceThinkingPhraseDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("让我考虑下如何完成任务", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (voiceThinkingPhraseDraft.trim() != voiceThinkingPhrase) {
                Button(
                  onClick = { viewModel.setVoiceThinkingPhrase(voiceThinkingPhraseDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = { greetingPreviewPlayer.playText(voiceThinkingPhraseDraft.trim().ifEmpty { voiceThinkingPhrase }) },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
          HorizontalDivider(color = mobileBorder)
          // ── tool calls prompt (agent) ──
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "执行中提示" else "Tool-Calls Prompt",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "代理调用工具时播放的语音" else "Played when the agent is calling tools",
                style = mobileCallout,
              )
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              if (appLanguage == AppLanguage.ChineseSimplified) "启用该提示音" else "Enable this prompt",
              style = mobileCallout,
              color = mobileTextSecondary,
            )
            Switch(
              checked = voiceToolCallsEnabled,
              onCheckedChange = { viewModel.setVoiceToolCallsEnabled(it) },
            )
          }
          OutlinedTextField(
            value = voiceToolCallsPhraseDraft,
            onValueChange = { voiceToolCallsPhraseDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            singleLine = true,
            placeholder = { Text("任务在执行中，还需一些时间", style = mobileCallout, color = mobileTextTertiary) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (voiceToolCallsPhraseDraft.trim() != voiceToolCallsPhrase) {
                Button(
                  onClick = { viewModel.setVoiceToolCallsPhrase(voiceToolCallsPhraseDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = { greetingPreviewPlayer.playText(voiceToolCallsPhraseDraft.trim().ifEmpty { voiceToolCallsPhrase }) },
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, mobileAccent),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileAccent),
            ) {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "试听" else "Preview",
                style = mobileCallout,
              )
            }
          }
        }
      }

      // ── TTS Format Hint ──
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, mobileBorder, RoundedCornerShape(14.dp))
            .background(mobileCardSurface, RoundedCornerShape(14.dp)),
        ) {
          ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = listItemColors,
            headlineContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "语音 TTS 格式提示" else "Voice TTS Format Hint",
                style = mobileHeadline,
              )
            },
            supportingContent = {
              Text(
                if (appLanguage == AppLanguage.ChineseSimplified) "附加在每条语音消息末尾，提示 AI 以适合语音播放的格式回复" else "Appended to each voice message to guide TTS-friendly AI responses",
                style = mobileCallout,
              )
            },
          )
          OutlinedTextField(
            value = voiceTtsHintDraft,
            onValueChange = { voiceTtsHintDraft = it },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .padding(bottom = 12.dp),
            minLines = 2,
            maxLines = 5,
            placeholder = { Text(
              if (appLanguage == AppLanguage.ChineseSimplified)
                "例：（语音模式：请用简洁口语回答，避免Markdown格式和特殊符号）"
              else
                "e.g. (Voice mode: reply concisely in plain speech, avoid Markdown)",
              style = mobileCallout, color = mobileTextTertiary,
            ) },
            colors = settingsTextFieldColors(),
            textStyle = mobileCaption1,
            trailingIcon = {
              if (voiceTtsHintDraft.trim() != voiceTtsHint) {
                Button(
                  onClick = { viewModel.setVoiceTtsHint(voiceTtsHintDraft.trim()) },
                  modifier = Modifier.padding(end = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                  colors = settingsPrimaryButtonColors(),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                  Text(stringResource(R.string.common_save), style = mobileCallout)
                }
              }
            },
          )
        }
      }

      // ── Voice Print ──
      item {
        Text(
          if (appLanguage == AppLanguage.ChineseSimplified) "声纹身份" else "VOICE PRINT",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
          color = mobileAccent,
        )
      }
      item {
        VoicePrintSettingsCard(viewModel = viewModel)
      }

      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, mobileBorder, RoundedCornerShape(14.dp))
            .background(mobileCardSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              if (appLanguage == AppLanguage.ChineseSimplified) "声纹匹配门槛" else "Voice Match Threshold",
              style = mobileCallout.copy(fontWeight = FontWeight.Medium),
              color = mobileText,
            )
            Text(
              "%.2f".format(identityAsrThreshold),
              style = mobileCallout,
              color = mobileAccent,
            )
          }
          Slider(
            value = identityAsrThreshold,
            onValueChange = { viewModel.setIdentityAsrThreshold(it) },
            valueRange = 0.10f..0.80f,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            if (appLanguage == AppLanguage.ChineseSimplified)
              "较低 = 更宽松 · 较高 = 更严格（默认 0.45）"
            else
              "Lower = more lenient · Higher = stricter (default 0.45)",
            style = mobileCaption2,
            color = mobileTextSecondary,
          )
        }
      }

      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun settingsTextFieldColors() =
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
private fun Modifier.settingsRowModifier() =
  this
    .fillMaxWidth()
    .border(width = 1.dp, color = mobileBorder, shape = RoundedCornerShape(14.dp))
    .background(mobileCardSurface, RoundedCornerShape(14.dp))

@Composable
private fun settingsPrimaryButtonColors() =
  ButtonDefaults.buttonColors(
    containerColor = mobileAccent,
    contentColor = Color.White,
    disabledContainerColor = mobileAccent.copy(alpha = 0.45f),
    disabledContentColor = Color.White.copy(alpha = 0.9f),
  )

@Composable
private fun settingsDangerButtonColors() =
  ButtonDefaults.buttonColors(
    containerColor = mobileDanger,
    contentColor = Color.White,
    disabledContainerColor = mobileDanger.copy(alpha = 0.45f),
    disabledContentColor = Color.White.copy(alpha = 0.9f),
  )

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}

private fun openNotificationListenerSettings(context: Context) {
  val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
  runCatching {
    context.startActivity(intent)
  }.getOrElse {
    openAppSettings(context)
  }
}

private fun hasNotificationsPermission(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < 33) return true
  return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
  return DeviceNotificationListenerService.isAccessEnabled(context)
}

private fun hasMotionCapabilities(context: Context): Boolean {
  val sensorManager = context.getSystemService(SensorManager::class.java) ?: return false
  return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
          sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
}
