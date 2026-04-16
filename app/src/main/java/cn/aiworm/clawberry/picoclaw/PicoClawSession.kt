package clawberry.aiworm.cn.picoclaw

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Events emitted from the PicoClaw WebSocket
// Mirrors the message types in chat_picoclaw.py recv_until_done()
// ---------------------------------------------------------------------------
sealed class PcEvent {
    /** WS handshake complete. */
    data object Connected : PcEvent()

    /** Server started generating a response. */
    data object TypingStart : PcEvent()

    /** Server finished generating (message.create usually follows immediately). */
    data object TypingStop : PcEvent()

    /** New assistant message finalised. */
    data class MessageCreate(val messageId: String, val content: String) : PcEvent()

    /** Server is streaming an in-place update to an existing message. */
    data class MessageUpdate(val messageId: String, val content: String) : PcEvent()

    /** Server-reported error. */
    data class Errored(val message: String) : PcEvent()

    /** WS closed. */
    data object Disconnected : PcEvent()
}

/** Response from GET /api/pico/token (web-backend mode 1). */
data class PicoTokenResponse(
    val token: String,
    val wsUrl: String,
    val enabled: Boolean,
)

// ---------------------------------------------------------------------------
// Low-level PicoClaw session
//
// Mirrors the two-mode flow from chat_picoclaw.py:
//
//  Mode 1 (web backend :18800):
//    1. GET  http://host:18800/api/pico/token  →  { token, ws_url, enabled }
//    2. WS   ws_url?session_id=<id>
//       Auth: Authorization: Bearer <token>  +  Sec-WebSocket-Protocol: token.<token>
//
//  Mode 2 (direct :18790):
//    1. WS   ws://host:18790/pico/ws?session_id=<id>
//       Auth: Authorization: Bearer <token>  +  Sec-WebSocket-Protocol: token.<token>
// ---------------------------------------------------------------------------
class PicoClawSession(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Mode 1 — GET /api/pico/token
    // -------------------------------------------------------------------------
    suspend fun fetchToken(host: String, webPort: Int): PicoTokenResponse =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("http://$host:$webPort/api/pico/token")
                .get()
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val body = runCatching { response.body?.string() }.getOrNull() ?: ""
                            cont.resumeWithException(
                                IOException("Token fetch failed (${response.code}): $body"),
                            )
                            return
                        }
                        val body = response.body?.string() ?: "{}"
                        val obj = json.parseToJsonElement(body).jsonObject
                        val token = obj["token"]?.jsonPrimitive?.contentOrNull ?: ""
                        val wsUrl = obj["ws_url"]?.jsonPrimitive?.contentOrNull ?: ""
                        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                        if (token.isBlank() || wsUrl.isBlank()) {
                            cont.resumeWithException(
                                IOException("Invalid /api/pico/token response — is the pico channel enabled?"),
                            )
                            return
                        }
                        cont.resume(PicoTokenResponse(token = token, wsUrl = wsUrl, enabled = enabled))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    // -------------------------------------------------------------------------
    // Connect WS (both modes)
    //
    // Sends BOTH auth methods for maximum compatibility:
    //   • Authorization: Bearer <token>         — survives reverse proxies
    //   • Sec-WebSocket-Protocol: token.<value> — browser-native fallback
    //
    // When token is blank (proxy/transparent mode) auth headers are omitted.
    // -------------------------------------------------------------------------
    fun connectWs(
        wsUrl: String,
        token: String,
        onEvent: (PcEvent) -> Unit,
    ): WebSocket {
        val sessionId = UUID.randomUUID().toString()
        val sep = if ("?" in wsUrl) "&" else "?"
        val fullUrl = "$wsUrl${sep}session_id=$sessionId"

        val req = Request.Builder()
            .url(fullUrl)
            .apply {
                // Only send auth headers when we have a real token.
                // Blank = proxy/transparent mode — server handles auth itself.
                if (token.isNotBlank()) {
                    header("Authorization", "Bearer $token")
                    header("Sec-WebSocket-Protocol", "token.$token")
                }
            }
            .build()

        return client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onEvent(PcEvent.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = json.parseToJsonElement(text).jsonObject
                        val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return
                        val payload = runCatching { msg["payload"]?.jsonObject }.getOrNull()

                        when (type) {
                            "typing.start" -> onEvent(PcEvent.TypingStart)
                            "typing.stop" -> onEvent(PcEvent.TypingStop)

                            "message.create" -> {
                                val messageId = payload?.get("message_id")
                                    ?.jsonPrimitive?.contentOrNull ?: ""
                                val content = payload?.get("content")
                                    ?.jsonPrimitive?.contentOrNull ?: ""
                                onEvent(PcEvent.MessageCreate(messageId = messageId, content = content))
                            }

                            "message.update" -> {
                                val messageId = payload?.get("message_id")
                                    ?.jsonPrimitive?.contentOrNull ?: ""
                                val content = payload?.get("content")
                                    ?.jsonPrimitive?.contentOrNull ?: ""
                                onEvent(PcEvent.MessageUpdate(messageId = messageId, content = content))
                            }

                            "error" -> {
                                val message = payload?.get("message")
                                    ?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                onEvent(PcEvent.Errored(message = message))
                            }

                            "pong" -> { /* ignore — keep-alive */ }
                            // All other frames silently ignored per chat_picoclaw.py
                        }
                    } catch (_: Exception) { /* malformed frame */ }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onEvent(PcEvent.Disconnected)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent(PcEvent.Errored(t.message ?: "Connection failed"))
                    onEvent(PcEvent.Disconnected)
                }
            },
        )
    }

    // -------------------------------------------------------------------------
    // Connect via clawproxy (transparent mode)
    //
    // Treats clawproxy exactly like a PicoClaw gateway:
    //   • Same endpoint:  /pico/ws
    //   • Different port: caller passes proxyPort (default 18780)
    //   • No token:       clawproxy handles auth on its side
    // -------------------------------------------------------------------------
    fun connectViaProxy(
        host: String,
        port: Int,
        onEvent: (PcEvent) -> Unit,
    ): WebSocket = connectWs(wsUrl = "ws://$host:$port/pico/ws", token = "", onEvent)
}

