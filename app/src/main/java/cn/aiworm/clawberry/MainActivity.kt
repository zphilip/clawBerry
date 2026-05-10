package clawberry.aiworm.cn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import clawberry.aiworm.cn.ui.RootScreen
import clawberry.aiworm.cn.ui.OpenClawTheme
import clawberry.aiworm.cn.picoclaw.PicoClawViewModel
import clawberry.aiworm.cn.voice.MicCaptureManager
import clawberry.aiworm.cn.zeroclaw.ZeroClawViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
  private val viewModel: MainViewModel by viewModels()
  private val zcViewModel: ZeroClawViewModel by viewModels()
  private val picoViewModel: PicoClawViewModel by viewModels()
  private lateinit var permissionRequester: PermissionRequester
  private var didAttachRuntimeUi = false
  private var didStartNodeService = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    permissionRequester = PermissionRequester(this)

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.preventSleep.collect { enabled ->
          if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.runtimeInitialized.collect { ready ->
          if (!ready || didAttachRuntimeUi) return@collect
          viewModel.attachRuntimeUi(owner = this@MainActivity, permissionRequester = permissionRequester)
          didAttachRuntimeUi = true
          if (!didStartNodeService) {
            NodeForegroundService.start(this@MainActivity)
            didStartNodeService = true
          }
        }
      }
    }

    setContent {
      OpenClawTheme {
        Surface(modifier = Modifier) {
          RootScreen(viewModel = viewModel, zcViewModel = zcViewModel, picoViewModel = picoViewModel)
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    viewModel.setForeground(true)
    // Hide the floating bubble when the app itself is in the foreground.
    FloatingOverlayService.stop(this)
  }

  override fun onStop() {
    viewModel.setForeground(false)
    // Auto-enable OpenClaw voice only if a mic is actually running right now (i.e. globalMicEnabled
    // is true) AND no gateway mic (ZeroClaw/PicoClaw) is the one holding it.
    //
    // Using globalMicEnabled instead of viewModel.micEnabled (prefs.talkEnabled) is critical:
    // prefs.talkEnabled stays true even after ZeroClaw/PicoClaw steals mic ownership via
    // stopForHandoff(), so it can't tell us whether OpenClaw actually has the mic.
    val gatewayMicActive = zcViewModel.micEnabled.value || picoViewModel.micEnabled.value
    val anyMicCurrentlyActive = MicCaptureManager.globalMicEnabled.value
    if (anyMicCurrentlyActive &&
        !gatewayMicActive &&
        !MicCaptureManager.userExplicitlyDisabledMic &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      viewModel.setMicEnabled(true)
    }
    // Show the floating bubble when the user switches to another app.
    if (viewModel.floatingOverlayEnabled.value && Settings.canDrawOverlays(this)) {
      FloatingOverlayService.start(this)
    }
    super.onStop()
  }
}
