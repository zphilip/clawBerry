package ai.openclaw.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.openclaw.app.R
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.picoclaw.PicoClawViewModel
import ai.openclaw.app.zeroclaw.ZeroClawViewModel

private enum class RootTab(
  val label: String,
  val iconRes: Int,
  val tinted: Boolean = false,  // true → apply tint; false → render full colour
) {
  OpenClaw(label = "OpenClaw",  iconRes = R.drawable.ic_openclaw,  tinted = false),
  ZeroClaw(label = "ZeroClaw",  iconRes = R.drawable.ic_zeroclaw,  tinted = false),
  PicoClaw(label = "PicoClaw",  iconRes = R.drawable.ic_picoclaw,  tinted = false),
  Settings(label = "Settings",  iconRes = R.drawable.ic_settings_tab, tinted = true),
}

@Composable
fun RootScreen(viewModel: MainViewModel, zcViewModel: ZeroClawViewModel, picoViewModel: PicoClawViewModel) {
  var activeRoot by rememberSaveable { mutableStateOf(RootTab.OpenClaw) }
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()

  Column(modifier = Modifier.fillMaxSize()) {
    // ── Content area (fills remaining space) ─────────────────────────────────
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when (activeRoot) {
        RootTab.OpenClaw ->
          if (!onboardingCompleted) {
            OnboardingFlow(viewModel = viewModel, modifier = Modifier.fillMaxSize())
          } else {
            PostOnboardingTabs(
              viewModel = viewModel,
              zcViewModel = zcViewModel,
              modifier = Modifier.fillMaxSize(),
            )
          }

        RootTab.ZeroClaw ->
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(mobileBackgroundGradient),
          ) {
            ZeroClawChatScreen(viewModel = zcViewModel)
          }

        RootTab.PicoClaw ->
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(mobileBackgroundGradient),
          ) {
            PicoClawScreen(viewModel = picoViewModel)
          }

        RootTab.Settings ->
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(mobileBackgroundGradient),
          ) {
            SettingsSheet(viewModel = viewModel)
          }
      }
    }

    // ── Root tab bar ──────────────────────────────────────────────────────────
    RootTabBar(activeRoot = activeRoot, onSelect = { activeRoot = it })
  }
}

@Composable
private fun RootTabBar(
  activeRoot: RootTab,
  onSelect: (RootTab) -> Unit,
) {
  val safeInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = mobileAccentSoft,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
    shadowElevation = 6.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .windowInsetsPadding(safeInsets)
          .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RootTab.entries.forEach { tab ->
        val active = tab == activeRoot
        Surface(
          onClick = { onSelect(tab) },
          modifier = Modifier.weight(1f).heightIn(min = 52.dp),
          shape = RoundedCornerShape(16.dp),
          color = if (active) mobileAccentSoft else Color.Transparent,
          border = if (active) BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting) else null,
          shadowElevation = 0.dp,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            val iconTint = if (active) mobileAccent else mobileTextTertiary
            Icon(
              painter = painterResource(id = tab.iconRes),
              contentDescription = tab.label,
              tint = if (tab.tinted) iconTint else Color.Unspecified,
              modifier = Modifier.size(24.dp),
            )
            if (active) {
              Text(
                text = tab.label,
                color = mobileAccent,
                style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

