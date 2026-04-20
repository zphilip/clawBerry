package clawberry.aiworm.cn.ui

import java.util.Base64
import java.util.Locale
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class GatewayEndpointConfig(
  val host: String,
  val port: Int,
  val tls: Boolean,
  val displayUrl: String,
)

internal data class GatewaySetupCode(
  val url: String,
  val bootstrapToken: String?,
  val token: String?,
  val password: String?,
)

internal data class GatewayConnectConfig(
  val host: String,
  val port: Int,
  val tls: Boolean,
  val bootstrapToken: String,
  val token: String,
  val password: String,
)

private val gatewaySetupJson = Json { ignoreUnknownKeys = true }

internal fun resolveGatewayConnectConfig(
  useSetupCode: Boolean,
  setupCode: String,
  manualHost: String,
  manualPort: String,
  manualTls: Boolean,
  fallbackToken: String,
  fallbackPassword: String,
): GatewayConnectConfig? {
  if (useSetupCode) {
    val setup = decodeGatewaySetupCode(setupCode) ?: return null
    val parsed = parseGatewayEndpoint(setup.url) ?: return null
    val setupBootstrapToken = setup.bootstrapToken?.trim().orEmpty()
    val sharedToken =
      when {
        !setup.token.isNullOrBlank() -> setup.token.trim()
        setupBootstrapToken.isNotEmpty() -> ""
        else -> fallbackToken.trim()
      }
    val sharedPassword =
      when {
        !setup.password.isNullOrBlank() -> setup.password.trim()
        setupBootstrapToken.isNotEmpty() -> ""
        else -> fallbackPassword.trim()
      }
    return GatewayConnectConfig(
      host = parsed.host,
      port = parsed.port,
      tls = parsed.tls,
      bootstrapToken = setupBootstrapToken,
      token = sharedToken,
      password = sharedPassword,
    )
  }

  val manualUrl = composeGatewayManualUrl(manualHost, manualPort, manualTls) ?: return null
  val parsed = parseGatewayEndpoint(manualUrl) ?: return null
  return GatewayConnectConfig(
    host = parsed.host,
    port = parsed.port,
    tls = parsed.tls,
    bootstrapToken = "",
    token = fallbackToken.trim(),
    password = fallbackPassword.trim(),
  )
}

internal fun parseGatewayEndpoint(rawInput: String): GatewayEndpointConfig? {
  val raw = rawInput.trim()
  if (raw.isEmpty()) return null

  val normalized = if (raw.contains("://")) raw else "https://$raw"
  val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
  val host = uri.host?.trim().orEmpty()
  if (host.isEmpty()) return null

  val scheme = uri.scheme?.trim()?.lowercase(Locale.US).orEmpty()
  val tls =
    when (scheme) {
      "ws", "http" -> false
      "wss", "https" -> true
      else -> true
    }
  val port = uri.port.takeIf { it in 1..65535 } ?: if (tls) 443 else 18789
  val displayUrl = "${if (tls) "https" else "http"}://$host:$port"

  return GatewayEndpointConfig(host = host, port = port, tls = tls, displayUrl = displayUrl)
}

internal fun decodeGatewaySetupCode(rawInput: String): GatewaySetupCode? {
  val trimmed = rawInput.trim()
  if (trimmed.isEmpty()) return null

  val padded =
    trimmed
      .replace('-', '+')
      .replace('_', '/')
      .let { normalized ->
        val remainder = normalized.length % 4
        if (remainder == 0) normalized else normalized + "=".repeat(4 - remainder)
      }

  return try {
    val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    val obj = parseJsonObject(decoded) ?: return null
    val url = jsonField(obj, "url").orEmpty()
    if (url.isEmpty()) return null
    val bootstrapToken = jsonField(obj, "bootstrapToken")
    val token = jsonField(obj, "token")
    val password = jsonField(obj, "password")
    GatewaySetupCode(url = url, bootstrapToken = bootstrapToken, token = token, password = password)
  } catch (_: IllegalArgumentException) {
    null
  }
}

internal fun resolveScannedSetupCode(rawInput: String): String? {
  val setupCode = resolveSetupCodeCandidate(rawInput) ?: return null
  return setupCode.takeIf { decodeGatewaySetupCode(it) != null }
}

internal data class GatewayScannedManualResult(
  val host: String? = null,
  val port: Int? = null,
  val tls: Boolean? = null,
  val token: String? = null,
)

/**
 * Parses a QR scan result for Manual mode.
 * Accepts:
 *   - A URL (http/https/ws/wss) with an optional `?token=` query parameter → fills host, port, tls, token.
 *   - A bare token string (no `://`) → fills token only.
 * Returns null only if the input is blank.
 */
internal fun resolveScannedManualConfig(rawInput: String): GatewayScannedManualResult? {
  val trimmed = rawInput.trim()
  if (trimmed.isEmpty()) return null

  // Looks like a URL
  if (trimmed.contains("://")) {
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
    val host = uri?.host?.trim()?.trim('[', ']').orEmpty()
    if (host.isNotEmpty()) {
      val scheme = uri?.scheme?.trim()?.lowercase(Locale.US).orEmpty()
      val tls = when (scheme) {
        "ws", "http" -> false
        else -> true
      }
      val port = uri?.port?.takeIf { it in 1..65535 } ?: if (tls) 443 else 18789
      val token = uri?.query?.split("&")
        ?.firstOrNull { it.startsWith("token=") }
        ?.removePrefix("token=")
        ?.trim()
        ?.ifEmpty { null }
      return GatewayScannedManualResult(host = host, port = port, tls = tls, token = token)
    }
  }

  // Not a URL — treat the whole content as a raw token
  return GatewayScannedManualResult(token = trimmed)
}

internal fun composeGatewayManualUrl(hostInput: String, portInput: String, tls: Boolean): String? {
  val host = hostInput.trim()
  val port = portInput.trim().toIntOrNull() ?: return null
  if (host.isEmpty() || port !in 1..65535) return null
  val scheme = if (tls) "https" else "http"
  return "$scheme://$host:$port"
}

private fun parseJsonObject(input: String): JsonObject? {
  return runCatching { gatewaySetupJson.parseToJsonElement(input).jsonObject }.getOrNull()
}

private fun resolveSetupCodeCandidate(rawInput: String): String? {
  val trimmed = rawInput.trim()
  if (trimmed.isEmpty()) return null
  val qrSetupCode = parseJsonObject(trimmed)?.let { jsonField(it, "setupCode") }
  return qrSetupCode ?: trimmed
}

private fun jsonField(obj: JsonObject, key: String): String? {
  val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
  return value.ifEmpty { null }
}
