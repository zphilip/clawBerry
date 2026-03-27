package ai.openclaw.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import ai.openclaw.app.R
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.zeroclaw.ZeroClawViewModel

private enum class HomeTab(
  val label: String,
  val icon: ImageVector,
) {
  Connect(label = "Connect", icon = Icons.Default.CheckCircle),
  Chat(label = "Chat", icon = Icons.Default.ChatBubble),
  Voice(label = "Voice", icon = Icons.Default.RecordVoiceOver),
  Screen(label = "Screen", icon = Icons.AutoMirrored.Filled.ScreenShare),
}

private enum class StatusVisual {
  Connected,
  Connecting,
  Warning,
  Error,
  Offline,
}

@Composable
fun PostOnboardingTabs(
  viewModel: MainViewModel,
  zcViewModel: ZeroClawViewModel,
  modifier: Modifier = Modifier,
) {
  var activeTab by rememberSaveable { mutableStateOf(HomeTab.Connect) }

  // Stop TTS when user navigates away from voice tab
  LaunchedEffect(activeTab) {
    viewModel.setVoiceScreenActive(activeTab == HomeTab.Voice)
  }

  val statusText by viewModel.statusText.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()

  val statusVisual =
    remember(statusText, isConnected) {
      val lower = statusText.lowercase()
      when {
        isConnected -> StatusVisual.Connected
        lower.contains("connecting") || lower.contains("reconnecting") -> StatusVisual.Connecting
        lower.contains("pairing") || lower.contains("approval") || lower.contains("auth") -> StatusVisual.Warning
        lower.contains("error") || lower.contains("failed") -> StatusVisual.Error
        else -> StatusVisual.Offline
      }
    }

  val density = LocalDensity.current
  val imeVisible = WindowInsets.ime.getBottom(density) > 0
  val hideBottomTabBar = activeTab == HomeTab.Chat && imeVisible

  Scaffold(
    modifier = modifier,
    containerColor = Color.Transparent,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      TopStatusBar(
        statusText = statusText,
        statusVisual = statusVisual,
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .background(mobileBackgroundGradient),
    ) {
      when (activeTab) {
        HomeTab.Connect -> ConnectTabScreen(viewModel = viewModel)
        HomeTab.Chat -> ChatSheet(viewModel = viewModel)
        HomeTab.Voice -> VoiceTabScreen(viewModel = viewModel)
        HomeTab.Screen -> ScreenTabScreen(viewModel = viewModel)
      }
      if (!hideBottomTabBar) {
        VerticalTabRail(
          tabs = HomeTab.entries,
          activeTab = activeTab,
          onSelect = { activeTab = it },
          icon = { tab -> tab.icon },
          label = { tab -> tab.label },
          modifier = Modifier.align(Alignment.BottomEnd),
        )
      }
    }
  }
}

@Composable
private fun ScreenTabScreen(viewModel: MainViewModel) {
  val isConnected by viewModel.isConnected.collectAsState()
  LaunchedEffect(isConnected) {
    if (isConnected) {
      viewModel.refreshHomeCanvasOverviewIfConnected()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    CanvasScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
  }
}

@Composable
private fun TopStatusBar(
  statusText: String,
  statusVisual: StatusVisual,
) {
  val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

  val (chipBg, chipDot, chipText, chipBorder) =
    when (statusVisual) {
      StatusVisual.Connected ->
        listOf(
          mobileSuccessSoft,
          mobileSuccess,
          mobileSuccess,
          LocalMobileColors.current.chipBorderConnected,
        )
      StatusVisual.Connecting ->
        listOf(
          mobileAccentSoft,
          mobileAccent,
          mobileAccent,
          LocalMobileColors.current.chipBorderConnecting,
        )
      StatusVisual.Warning ->
        listOf(
          mobileWarningSoft,
          mobileWarning,
          mobileWarning,
          LocalMobileColors.current.chipBorderWarning,
        )
      StatusVisual.Error ->
        listOf(
          mobileDangerSoft,
          mobileDanger,
          mobileDanger,
          LocalMobileColors.current.chipBorderError,
        )
      StatusVisual.Offline ->
        listOf(
          mobileSurface,
          mobileTextTertiary,
          mobileTextSecondary,
          mobileBorder,
        )
    }

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
          painter = painterResource(id = R.drawable.ic_openclaw),
          contentDescription = "OpenClaw",
          modifier = Modifier.size(28.dp),
        )
        Text(
          text = "OpenClaw",
          style = mobileTitle2,
          color = mobileText,
        )
      }
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = chipBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, chipBorder),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            modifier = Modifier.padding(top = 1.dp),
            color = chipDot,
            shape = RoundedCornerShape(999.dp),
          ) {
            Box(modifier = Modifier.padding(4.dp))
          }
          Text(
            text = statusText.trim().ifEmpty { "Offline" },
            style = mobileCaption1,
            color = chipText,
            maxLines = 1,
          )
        }
      }
    }
  }
}

// Shared vertical floating tab rail used by all three screens.
// Tap the collapsed pill to expand; tap any tab to select + collapse.
@Composable
internal fun <T> VerticalTabRail(
  tabs: List<T>,
  activeTab: T,
  onSelect: (T) -> Unit,
  icon: (T) -> ImageVector,
  label: (T) -> String,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val safeInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

  Surface(
    modifier = modifier
      .windowInsetsPadding(safeInsets)
      .padding(end = 12.dp, bottom = 12.dp),
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
    shadowElevation = 8.dp,
  ) {
    Column(
      modifier = Modifier
        .animateContentSize(
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          ),
        )
        .padding(horizontal = 6.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (expanded) {
        // ── Expanded: all tabs ──────────────────────────────────────────────
        tabs.forEach { tab ->
          val active = tab == activeTab
          Surface(
            onClick = {
              onSelect(tab)
              expanded = false
            },
            shape = RoundedCornerShape(999.dp),
            color = if (active) mobileAccent else Color.Transparent,
            shadowElevation = 0.dp,
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(
                imageVector = icon(tab),
                contentDescription = label(tab),
                tint = if (active) Color.White else mobileTextSecondary,
                modifier = Modifier.size(18.dp),
              )
              if (active) {
                Text(
                  text = label(tab),
                  color = Color.White,
                  style = mobileCaption2.copy(fontWeight = FontWeight.Bold),
                  maxLines = 1,
                )
              }
            }
          }
        }
      } else {
        // ── Collapsed: active tab icon only ────────────────────────────────
        Surface(
          onClick = { expanded = true },
          shape = RoundedCornerShape(999.dp),
          color = mobileAccent,
          shadowElevation = 0.dp,
        ) {
          Box(modifier = Modifier.padding(10.dp)) {
            Icon(
              imageVector = icon(activeTab),
              contentDescription = label(activeTab),
              tint = Color.White,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}
