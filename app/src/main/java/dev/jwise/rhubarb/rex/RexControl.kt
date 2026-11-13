package dev.jwise.rhubarb.rex

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Error returned by the server in a `response` message. */
class RexError(val code: String, override val message: String, val status: Int) : Exception(message)

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    /** [generation] increments on every (re)connection that was *not* a resume, so state must be re-read. */
    data class Connected(val clientId: String, val resumed: Boolean, val generation: Int) : ConnState
    data class Failed(val reason: String) : ConnState
}

/**
 * The Rex control connection (`/api/control`, subprotocol `rex.control.v1`).
 *
 * Protocol per the server's own `/llms.txt`: send `hello` within 5s, receive `welcome`,
 * then `request` -> `response` matched by id, plus unsolicited `event` messages.
 * Reconnects automatically with the welcome's `reconnect_token` so the server keeps
 * our client (and its session attachments) across a brief network drop.
 *
 * [onConnected] runs after every welcome, before callers are released, so owners can
 * re-attach to sessions etc.
 */
class RexControl(
    val baseUrl: String,
    private val scope: CoroutineScope,
    private val clientName: String = "Rhubarb",
    /** The client's label, shown in Rex's client lists (e.g. "Pixel 9"). */
    private val clientLabel: () -> String? = { null },
    private val onConnected: suspend (RexControl, ConnState.Connected) -> Unit = { _, _ -> },
) {
    companion object {
        const val API_REVISION = 13
        private const val TAG = "RexControl"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // Server pings every 120s; we ping more often so a dead phone network is noticed fast.
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /** Accepts `host.ts.net`, `https://host.ts.net`, `host:port`, etc. Returns e.g. `wss://host.ts.net`. */
        fun normalizeBase(input: String): String {
            var s = input.trim().trimEnd('/')
            s = when {
                s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
                s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
                s.startsWith("wss://") || s.startsWith("ws://") -> s
                else -> "wss://$s"
            }
            return s.substringBefore("/api")
        }
    }

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 512)
    /** Inner event objects: `{"type": "...", "payload": {...}}` (the `event` envelope is removed). */
    val events: SharedFlow<JsonObject> = _events

    /** The API revision the server agreed to on the last welcome (0 until connected). */
    @Volatile var apiRevision = 0
        private set

    private var ws: WebSocket? = null
    private var reconnectToken: String? = null
    private var generation = 0
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private var welcome = CompletableDeferred<ConnState.Connected>()
    private var loopJob: Job? = null
    @Volatile private var stopped = false

    fun start() {
        if (loopJob?.isActive == true) return
        stopped = false
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        stopped = true
        loopJob?.cancel()
        // Close frame => server ends our client immediately (no 60s grace).
        ws?.close(1000, "bye")
        ws = null
        failPending("stopped")
        _state.value = ConnState.Idle
    }

    private suspend fun runLoop() {
        var backoff = 1000L
        while (!stopped) {
            val closed = CompletableDeferred<String>()
            welcome = CompletableDeferred()
            _state.value = ConnState.Connecting
            openSocket(closed)
            val reason = closed.await()
            failPending(reason)
            if (stopped) break
            _state.value = ConnState.Failed(reason)
            Log.i(TAG, "control closed: $reason; retrying in ${backoff}ms")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000)
            if (welcome.isCompleted) backoff = 1000L
        }
    }

    private fun openSocket(closed: CompletableDeferred<String>) {
        val req = Request.Builder()
            .url("$baseUrl/api/control")
            .header("Sec-WebSocket-Protocol", "rex.control.v1")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("api_revision", API_REVISION)
                    reconnectToken?.let { put("reconnect_token", it) }
                    putJsonObject("client") {
                        put("kind", "app")
                        put("platform", "other") // "android" isn't a known platform value yet.
                        put("name", clientName)
                        put("version", dev.jwise.rhubarb.BuildConfig.VERSION_NAME)
                        clientLabel()?.takeIf { it.isNotBlank() }?.let { put("label", it.take(40)) }
                    }
                }
                // Under 512 bytes, so OkHttp won't deflate it (as the protocol asks).
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { return }
                when (msg["type"]?.jsonPrimitive?.content) {
                    "welcome" -> {
                        val client = msg["client"]?.jsonObject
                        reconnectToken = client?.get("reconnect_token")?.jsonPrimitive?.content
                        apiRevision = msg["api_revision"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val resumed = client?.get("resumed")?.jsonPrimitive?.content == "true"
                        if (!resumed) generation++
                        val c = ConnState.Connected(
                            clientId = client?.get("id")?.jsonPrimitive?.content ?: "",
                            resumed = resumed,
                            generation = generation,
                        )
                        scope.launch {
                            try { onConnected(this@RexControl, c) } catch (e: Exception) { Log.w(TAG, "onConnected", e) }
                            _state.value = c
                            welcome.complete(c)
                        }
                    }
                    "response" -> {
                        val id = msg["id"]?.jsonPrimitive?.content ?: return
                        val d = pending.remove(id) ?: return
                        val err = msg["error"]
                        if (err != null && err is JsonObject) {
                            d.completeExceptionally(
                                RexError(
                                    err["code"]?.jsonPrimitive?.content ?: "internal",
                                    err["message"]?.jsonPrimitive?.content ?: "error",
                                    err["status"]?.jsonPrimitive?.int ?: 500,
                                )
                            )
                        } else d.complete(msg["payload"])
                    }
                    "event" -> {
                        val inner = msg["payload"] as? JsonObject ?: return
                        _events.tryEmit(inner)
                    }
                    else -> Unit // unknown types must be skipped
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                closed.complete(explainClose(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(explainFailure(baseUrl, t, response?.code))
            }
        })
    }

    private fun failPending(reason: String) {
        val all = pending.values.toList(); pending.clear()
        all.forEach { it.completeExceptionally(RexError("disconnected", reason, 503)) }
    }

    /** Wait for a live connection, then call [method]. Raw calls made from inside [onConnected] use [callNow]. */
    suspend fun call(method: String, payload: JsonObject? = null, timeoutMs: Long = 15_000): JsonElement? {
        withTimeout(timeoutMs) { welcome.await() }
        return callNow(method, payload, timeoutMs)
    }

    suspend fun callNow(method: String, payload: JsonObject? = null, timeoutMs: Long = 15_000): JsonElement? {
        val socket = ws ?: throw RexError("disconnected", "not connected", 503)
        val id = nextId.getAndIncrement().toString()
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        val req = buildJsonObject {
            put("type", "request"); put("id", id); put("method", method)
            if (payload != null) put("payload", payload)
        }
        if (!socket.send(req.toString())) {
            pending.remove(id)
            throw RexError("disconnected", "send failed", 503)
        }
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    /** Call a block method such as `com.superlogical.terminal.write`. */
    suspend fun blockCall(sessionId: String, blockId: String, method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? =
        call(method, buildJsonObject {
            put("session_id", sessionId); put("block_id", blockId); put("args", args)
        })
}

private val tailscaleHost = Regex("""\.ts\.net(:\d+)?$""", RegexOption.IGNORE_CASE)

/** Why the control connection couldn't be made, in words a person can act on. */
internal fun explainFailure(baseUrl: String, t: Throwable, httpCode: Int?): String {
    val host = baseUrl.substringAfter("://").substringBefore('/')
    val onTailnet = tailscaleHost.containsMatchIn(host)
    val tsHint = if (onTailnet) " Is Tailscale on and connected on this phone?" else ""
    return when {
        httpCode == 403 -> "The server turned this phone away. Sign in to Tailscale with the same account as the Mac."
        httpCode == 404 -> "That address answered, but it isn't a Rex server."
        httpCode != null && httpCode in 500..599 -> "The Rex server had an error ($httpCode). Try again in a moment."
        httpCode != null -> "The server answered with HTTP $httpCode, not Rex."
        t is java.net.UnknownHostException -> "Can't find $host.$tsHint Check the address."
        t is java.net.ConnectException -> "Nothing is accepting connections at $host. Is the Rex server running" +
            (if (onTailnet) " with Tailscale on (rex server tailscale on)?" else "?")
        t is java.net.SocketTimeoutException -> "$host didn't answer.$tsHint"
        t is java.net.NoRouteToHostException -> "No route to $host.$tsHint"
        t is javax.net.ssl.SSLHandshakeException || t is javax.net.ssl.SSLPeerUnverifiedException ->
            "$host's certificate isn't trusted, so the connection was refused for safety."
        t is java.io.EOFException -> "$host closed the connection before answering."
        else -> t.message ?: t.toString()
    }
}

/** Why the server closed the control connection. */
internal fun explainClose(code: Int, reason: String): String = when {
    code == 1008 && reason.contains("revision", ignoreCase = true) ->
        "This Rex server and Rhubarb don't share an API version: $reason. Update whichever is older."
    code == 1008 -> "The server refused the connection" + (if (reason.isNotBlank()) ": $reason" else ".")
    code == 1001 -> "The server is shutting down or restarting."
    reason.isNotBlank() -> "Connection closed: $reason"
    else -> "Connection closed ($code)."
}
