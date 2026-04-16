package clawberry.aiworm.cn.zeroclaw

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Events emitted from the ZeroClaw WebSocket
// Mirrors the message types defined in ws.ts / AgentChat.tsx
// ---------------------------------------------------------------------------
sealed class ZcEvent {
  /** WebSocket handshake completed. */
  data object Connected : ZcEvent()

  /** Streaming text chunk from the agent. */
  data class Chunk(val content: String) : ZcEvent()

  /** Final "done" frame – carries the complete assembled response. */
  data class Done(val fullResponse: String) : ZcEvent()

  /** Non-streaming single response message. */
  data class Message(val content: String) : ZcEvent()

  /** Agent invoked a tool. */
  data class ToolCall(val name: String, val args: String) : ZcEvent()

  /** Tool returned a result. */
  data class ToolResult(val output: String) : ZcEvent()

  /** Agent or transport error. */
  data class Errored(val message: String) : ZcEvent()

  /** Server rejected the token (401 / 403). */
  data object Unauthorized : ZcEvent()

  /** WebSocket was closed. */
  data object Disconnected : ZcEvent()
}

data class ZcHealth(
  val requirePairing: Boolean,
  val paired: Boolean,
)

data class ZcSessionInfo(
  val id: String,
  val lastActive: String,
  val messageCount: Int,
  val preview: String,
)

/**
 * Low-level ZeroClaw gateway session.
 *
 * Mirrors the three-step flow from chat.py:
 *  1. GET  /health
 *  2. POST /pair   (X-Pairing-Code header)
 *  3. WS   /ws/chat?token=…&session_id=…  (subprotocol zeroclaw.v1)
 */
class ZeroClawSession(private val client: OkHttpClient) {

  private val json = Json { ignoreUnknownKeys = true }

  // ---------------------------------------------------------------------------
  // Step 1 — GET /health
  // ---------------------------------------------------------------------------
  suspend fun health(host: String, port: Int): ZcHealth =
    suspendCancellableCoroutine { cont ->
      val req = Request.Builder()
        .url("http://$host:$port/health")
        .get()
        .build()
      val call = client.newCall(req)
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
          try {
            val body = response.body?.string() ?: "{}"
            val obj = json.parseToJsonElement(body).jsonObject
            cont.resume(
              ZcHealth(
                requirePairing = obj["require_pairing"]?.jsonPrimitive?.booleanOrNull ?: true,
                paired = obj["paired"]?.jsonPrimitive?.booleanOrNull ?: false,
              ),
            )
          } catch (e: Exception) {
            cont.resumeWithException(e)
          }
        }
      })
    }

  // ---------------------------------------------------------------------------
  // Step 2 — POST /pair  (mirrors api.ts pair())
  // ---------------------------------------------------------------------------
  suspend fun pair(host: String, port: Int, code: String): String =
    suspendCancellableCoroutine { cont ->
      val req = Request.Builder()
        .url("http://$host:$port/pair")
        .post(ByteArray(0).toRequestBody())
        .header("X-Pairing-Code", code)
        .build()
      val call = client.newCall(req)
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
          try {
            if (!response.isSuccessful) {
              val errBody = runCatching { response.body?.string() }.getOrNull() ?: ""
              cont.resumeWithException(IOException("Pairing failed (${response.code}): $errBody"))
              return
            }
            val body = response.body?.string() ?: "{}"
            val obj = json.parseToJsonElement(body).jsonObject
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
              ?: throw IOException("No token in pairing response")
            cont.resume(token)
          } catch (e: Exception) {
            cont.resumeWithException(e)
          }
        }
      })
    }

  // ---------------------------------------------------------------------------
  // Step 3 — WebSocket /ws/chat  (mirrors WebSocketClient in ws.ts)
  // ---------------------------------------------------------------------------
  fun connectChat(
    host: String,
    port: Int,
    token: String?,
    sessionId: String,
    onEvent: (ZcEvent) -> Unit,
  ): WebSocket {
    // sessionId is supplied by the caller and kept stable across reconnects
    val params = buildString {
      if (!token.isNullOrBlank()) {
        append("token=")
        append(token)
        append("&")
      }
      append("session_id=")
      append(sessionId)
    }
    val req = Request.Builder()
      .url("ws://$host:$port/ws/chat?$params")
      .header("Sec-WebSocket-Protocol", "zeroclaw.v1")
      .build()

    return client.newWebSocket(
      req,
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          onEvent(ZcEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          try {
            val msg = json.parseToJsonElement(text).jsonObject
            val event =
              when (val type = msg["type"]?.jsonPrimitive?.contentOrNull) {
                "chunk" ->
                  ZcEvent.Chunk(msg["content"]?.jsonPrimitive?.contentOrNull ?: "")

                "done" ->
                  ZcEvent.Done(
                    msg["full_response"]?.jsonPrimitive?.contentOrNull
                      ?: msg["content"]?.jsonPrimitive?.contentOrNull
                      ?: "",
                  )

                "message" ->
                  ZcEvent.Message(msg["content"]?.jsonPrimitive?.contentOrNull ?: "")

                "tool_call" ->
                  ZcEvent.ToolCall(
                    name = msg["name"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    args = msg["args"]?.toString() ?: "{}",
                  )

                "tool_result" ->
                  ZcEvent.ToolResult(
                    output = msg["output"]?.jsonPrimitive?.contentOrNull ?: "",
                  )

                "error" ->
                  ZcEvent.Errored(
                    message = msg["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                  )

                else -> {
                  // Unknown frame type — silently ignore per chat.py behaviour
                  return
                }
              }
            onEvent(event)
          } catch (_: Exception) {
            // Malformed frame — ignore
          }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          onEvent(ZcEvent.Disconnected)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          val code = response?.code
          if (code == 401 || code == 403) {
            // Unauthorized: emit Unauthorized only — the Disconnected state
            // is implied and handled inside Unauthorized handler in the ViewModel
            onEvent(ZcEvent.Unauthorized)
            onEvent(ZcEvent.Disconnected)  // still needed to null out webSocket
          } else {
            onEvent(ZcEvent.Errored(t.message ?: "Connection failed"))
            onEvent(ZcEvent.Disconnected)
          }
        }
      },
    )
  }

  // ---------------------------------------------------------------------------
  // Step 3b — Connect via clawproxy (transparent mode)
  //
  // Treats clawproxy exactly like a ZeroClaw gateway:
  //   • Same endpoint:  /ws/chat
  //   • Same protocol:  zeroclaw.v1
  //   • Different port: caller passes proxyPort (default 18780)
  //   • No token:       clawproxy handles auth on its side
  // ---------------------------------------------------------------------------
  fun connectViaProxy(
    host: String,
    port: Int,
    sessionId: String,
    onEvent: (ZcEvent) -> Unit,
  ): WebSocket = connectChat(host, port, token = null, sessionId, onEvent)

  // ---------------------------------------------------------------------------
  // GET /api/sessions  — list all stored sessions
  // ---------------------------------------------------------------------------
  suspend fun fetchSessions(host: String, port: Int, token: String?): List<ZcSessionInfo> =
    suspendCancellableCoroutine { cont ->
      val reqBuilder = Request.Builder().url("http://$host:$port/api/sessions").get()
      if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")
      val call = client.newCall(reqBuilder.build())
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
          try {
            if (!response.isSuccessful) { cont.resume(emptyList()); return }
            val body = response.body?.string() ?: "[]"
            val arr = json.parseToJsonElement(body).jsonArray
            val list = arr.mapNotNull { el ->
              val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
              val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
              ZcSessionInfo(
                id = id,
                lastActive = obj["last_active"]?.jsonPrimitive?.contentOrNull ?: "",
                messageCount = obj["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
                preview = obj["preview"]?.jsonPrimitive?.contentOrNull ?: "",
              )
            }
            cont.resume(list)
          } catch (_: Exception) {
            cont.resume(emptyList())
          }
        }
      })
    }

  // ---------------------------------------------------------------------------
  // GET /api/sessions/{id}/messages  — retrieve stored messages for a session
  // ---------------------------------------------------------------------------
  suspend fun fetchSessionMessages(
    host: String,
    port: Int,
    token: String?,
    sessionId: String,
  ): List<ZcChatMessage> =
    suspendCancellableCoroutine { cont ->
      val reqBuilder = Request.Builder()
        .url("http://$host:$port/api/sessions/$sessionId/messages")
        .get()
      if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")
      val call = client.newCall(reqBuilder.build())
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
          try {
            if (!response.isSuccessful) { cont.resume(emptyList()); return }
            val body = response.body?.string() ?: "[]"
            val arr = json.parseToJsonElement(body).jsonArray
            val list = arr.mapNotNull { el ->
              val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
              val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
              val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
              ZcChatMessage(role = role, content = content)
            }
            cont.resume(list)
          } catch (_: Exception) {
            cont.resume(emptyList())
          }
        }
      })
    }
}
