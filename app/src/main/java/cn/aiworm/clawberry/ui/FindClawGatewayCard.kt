package clawberry.aiworm.cn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import clawberry.aiworm.cn.gateway.ClawGatewayScanner
import clawberry.aiworm.cn.gateway.ClawServiceType
import clawberry.aiworm.cn.gateway.DiscoveredGateway

/**
 * "Find Claw Gateway" — self-contained card that uses NSD to scan the local
 * network for `_clawberry._tcp.` services and differentiates them by their
 * TXT record `type` attribute:
 *
 * • `type=web_ui`   → **Open** button (launches browser)
 * • `type=gateway`   → **Use** button (fills ZeroClaw connection fields)
 * • `type=picoclaw`  → **Use** button (fills PicoClaw connection fields)
 *
 * @param serviceTypeFilter When non-null, only services whose TXT `type`
 *   attribute matches this value are shown. Pass [ClawServiceType.ATTR_GATEWAY]
 *   in ZeroClaw settings, [ClawServiceType.ATTR_PICOCLAW] in PicoClaw settings,
 *   etc. Pass `null` to show all discovered services.
 */
@Composable
internal fun FindClawGatewayCard(
    onGatewaySelected: (host: String, port: Int, serviceName: String) -> Unit,
    serviceTypeFilter: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scanner = remember { ClawGatewayScanner(context) }
    val allResults by scanner.discovered.collectAsState()
    // Derive filtered list inline — no remember() needed, list is tiny and
    // remember() with a List key uses structural equality which can suppress
    // updates when new resolved services arrive.
    val results = if (serviceTypeFilter == null) allResults
                  else allResults.filter { it.attributes["type"]?.lowercase() == serviceTypeFilter }
    val isScanning by scanner.scanning.collectAsState()
    var hasScanned by remember { mutableStateOf(false) }

    // Automatically stop discovery when this composable leaves the tree.
    DisposableEffect(scanner) {
        onDispose { scanner.stopDiscovery() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Header ─────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = mobileAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Find Claw Gateway",
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileText,
                )
            }

            Text(
                text = "Scan your local network for gateways broadcasting via mDNS (avahi / Bonjour).",
                style = mobileCaption1,
                color = mobileTextTertiary,
            )

            // ── Scan / Stop button ──────────────────────────────────────
            if (isScanning) {
                OutlinedButton(
                    onClick = {
                        scanner.stopDiscovery()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, mobileBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileTextSecondary),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = mobileAccent,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning…", style = mobileCallout)
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop scan",
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Button(
                    onClick = {
                        hasScanned = true
                        scanner.startDiscovery()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = mobileAccent),
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasScanned) "Scan Again" else "Start Scan",
                        style = mobileCallout.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            // ── Results ─────────────────────────────────────────────────
            if (results.isNotEmpty()) {
                Text(
                    text = "${results.size} gateway${if (results.size > 1) "s" else ""} found",
                    style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileSuccess,
                )
                results.forEach { gw ->
                    DiscoveredGatewayRow(
                        gateway = gw,
                        onOpen = if (gw.isWebUi) {
                            {
                                val url = "http://${gw.host}:${gw.port}"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        } else {
                            null
                        },
                        onUse = if (!gw.isWebUi) {
                            {
                                scanner.stopDiscovery()
                                onGatewaySelected(gw.host, gw.port, gw.serviceName)
                            }
                        } else {
                            null
                        },
                    )
                }
            } else if (hasScanned && !isScanning) {
                Text(
                    text = "No gateways found. Make sure your gateway device is on the same network.",
                    style = mobileCaption1,
                    color = mobileTextTertiary,
                )
            }
        }
    }
}

/**
 * A single discovered gateway row. Shows a type badge plus
 * either an **Open** button (Dashboard / web_ui) or a **Use** button (ZeroClaw / PicoClaw).
 */
@Composable
private fun DiscoveredGatewayRow(
    gateway: DiscoveredGateway,
    onOpen: (() -> Unit)?,
    onUse: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = mobileAccentSoft,
        border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = mobileAccent,
                modifier = Modifier.size(18.dp),
            )

            // ── Service info ────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gateway.serviceName,
                    style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
                    color = mobileText,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Type badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = mobileAccent.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = gateway.typeBadge,
                            style = mobileCaption2.copy(fontWeight = FontWeight.Bold),
                            color = mobileAccent,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = "${gateway.host}:${gateway.port}",
                        style = mobileCaption1,
                        color = mobileTextSecondary,
                    )
                }
            }

            // ── Open button (ClawBerry only) ────────────────────────
            if (onOpen != null) {
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(8.dp),
                    color = mobileAccent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = "Open in browser",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Open",
                            style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }

            // ── Use button (ZeroClaw / PicoClaw only) ───────────────
            if (onUse != null) {
                Surface(
                    onClick = onUse,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, LocalMobileColors.current.chipBorderConnecting),
                ) {
                    Text(
                        text = "Use",
                        style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
                        color = mobileAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
