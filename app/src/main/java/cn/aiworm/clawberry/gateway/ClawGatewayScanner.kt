package clawberry.aiworm.cn.gateway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All ClawBerry services publish under the same mDNS type and are
 * differentiated by their TXT record `type` attribute:
 *
 * | `type` attr  | Meaning                | Action       |
 * |--------------|------------------------|--------------|
 * | `web_ui`     | ClawBerry Dashboard    | Open browser |
 * | `zeroclaw`   | ZeroClaw Gateway       | Use (fill)   |
 * | `picoclaw`   | PicoClaw Gateway       | Use (fill)   |
 */
object ClawServiceType {
    /** The single mDNS service type broadcast by all ClawBerry services. */
    const val CLAWBERRY = "_clawberry._tcp."

    // TXT record "type" attribute values
    const val ATTR_WEB_UI = "web_ui"
    const val ATTR_GATEWAY = "zeroclaw"
    const val ATTR_PICOCLAW = "picoclaw"
}

/**
 * Result from an NSD scan — one discovered service.
 */
data class DiscoveredGateway(
    val serviceName: String,
    val host: String,
    val port: Int,
    val serviceType: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    /** The `type` TXT attribute published by avahi (`web_ui`, `zeroclaw`, `picoclaw`). */
    private val attrType: String get() = attributes["type"]?.lowercase().orEmpty()

    /** Human-readable display label. */
    val displayLabel: String get() = "$serviceName  ($host:$port)"

    /** `true` when this is the ClawBerry web dashboard (browser-openable). */
    val isWebUi: Boolean get() = attrType == ClawServiceType.ATTR_WEB_UI

    /** `true` when this is a ZeroClaw gateway. */
    val isZeroClaw: Boolean get() = attrType == ClawServiceType.ATTR_GATEWAY

    /** `true` when this is a PicoClaw gateway. */
    val isPicoClaw: Boolean get() = attrType == ClawServiceType.ATTR_PICOCLAW

    /** Short badge label shown in the UI row. */
    val typeBadge: String
        get() = when {
            isWebUi -> "Dashboard"
            isZeroClaw -> "ZeroClaw"
            isPicoClaw -> "PicoClaw"
            else -> attrType.ifEmpty { "unknown" }
        }
}

/**
 * Lightweight NSD scanner that discovers all `_clawberry._tcp.` services on
 * the local network and differentiates them by their TXT `type` attribute.
 *
 * **Important**: Android's deprecated `NsdManager.resolveService()` can only
 * handle **one resolve at a time** — concurrent calls silently fail with
 * `FAILURE_ALREADY_ACTIVE`.  This scanner queues resolve requests and
 * processes them sequentially.
 *
 * Usage from Compose:
 * ```kotlin
 * val scanner = remember { ClawGatewayScanner(context) }
 * DisposableEffect(scanner) {
 *     scanner.startDiscovery()
 *     onDispose { scanner.stopDiscovery() }
 * }
 * val results by scanner.discovered.collectAsState()
 * ```
 */
class ClawGatewayScanner(context: Context) {

    private val nsd: NsdManager = context.getSystemService(NsdManager::class.java)
    private val tag = "ClawGatewayScanner"

    private val found = ConcurrentHashMap<String, DiscoveredGateway>()

    private val _discovered = MutableStateFlow<List<DiscoveredGateway>>(emptyList())
    val discovered: StateFlow<List<DiscoveredGateway>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile private var listenerRegistered = false

    // ── Resolve queue (Android NSD can only resolve one at a time) ───────

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return   // another resolve in flight
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        resolveOne(next)
    }

    // ── Discovery listener ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(tag, "Discovery start failed: error $errorCode")
            listenerRegistered = false
            _scanning.value = false
        }

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(tag, "Discovery stop failed: error $errorCode")
        }

        override fun onDiscoveryStarted(type: String) {
            Log.d(tag, "Discovery started for $type")
            _scanning.value = true
        }

        override fun onDiscoveryStopped(type: String) {
            Log.d(tag, "Discovery stopped")
            _scanning.value = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(tag, "Found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            found.remove(serviceInfo.serviceName)
            publish()
        }
    }

    // ── Resolve (one at a time) ─────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun resolveOne(serviceInfo: NsdServiceInfo) {
        nsd.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(tag, "Resolve failed for ${info.serviceName}: error $errorCode")
                    resolving.set(false)
                    drainResolveQueue()           // try next in queue
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    // Strip IPv6 scope ID: "fe80::1%wlan0" → "fe80::1"
                    val host = resolved.host?.hostAddress
                        ?.substringBefore('%')
                        ?.takeIf { it.isNotBlank() }
                        ?: run {
                            resolving.set(false)
                            drainResolveQueue()
                            return
                        }
                    val port = resolved.port
                    if (port <= 0) {
                        resolving.set(false)
                        drainResolveQueue()
                        return
                    }

                    // Normalise keys to lowercase so "Type" and "type" both match
                    val attrs = mutableMapOf<String, String>()
                    resolved.attributes.forEach { (k, v) ->
                        attrs[k.lowercase()] = v?.let { String(it, Charsets.UTF_8) } ?: ""
                    }

                    val gw = DiscoveredGateway(
                        serviceName = resolved.serviceName,
                        host = host,
                        port = port,
                        serviceType = ClawServiceType.CLAWBERRY,
                        attributes = attrs,
                    )
                    found[resolved.serviceName] = gw
                    publish()
                    Log.d(tag, "Resolved: ${gw.displayLabel} [type=${attrs["type"]}]")

                    resolving.set(false)
                    drainResolveQueue()           // resolve next queued service
                }
            },
        )
    }

    private fun publish() {
        _discovered.value = found.values.sortedBy { it.serviceName.lowercase() }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (listenerRegistered) return
        found.clear()
        resolveQueue.clear()
        _discovered.value = emptyList()
        try {
            nsd.discoverServices(
                ClawServiceType.CLAWBERRY,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
            listenerRegistered = true
        } catch (e: Throwable) {
            Log.w(tag, "Failed to start discovery: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (!listenerRegistered) return
        try {
            nsd.stopServiceDiscovery(discoveryListener)
        } catch (_: Throwable) {
            // best-effort
        }
        listenerRegistered = false
        resolveQueue.clear()
        _scanning.value = false
    }
}
