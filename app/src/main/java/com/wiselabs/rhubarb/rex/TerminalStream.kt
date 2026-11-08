package com.wiselabs.rhubarb.rex

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface TermEvent {
    /** Clear the emulator; a fresh screen follows. */
    data object Reset : TermEvent
    data class Output(val bytes: ByteArray) : TermEvent
    data class Resize(val cols: Int, val rows: Int, val owner: String?) : TermEvent
    data class Theme(val json: JsonObject) : TermEvent
    data class ChildExited(val code: Int) : TermEvent
    data class Status(val text: String?) : TermEvent
    data object Closed : TermEvent
}

/**
 * One terminal block's output, via `/api/data` with subprotocol `rex.data.terminal.v1`.
 *
 * The stream begins with a binary Ghostty snapshot ("GHOSTSNP...") followed by a `resize` Rex
 * message tagged with the random channel nonce (`ESC _ rex1;<nonce>;`). The Ghostty renderer
 * restores that snapshot directly (see [restoreSnapshot]), giving the Mac's exact screen and
 * scrollback. Otherwise (xterm.js, or a snapshot this build can't decode) the snapshot is
 * dropped and the emulator is seeded from `com.superlogical.terminal.format` (`format: "vt"`).
 * From then on the stream is ordinary VT output.
 */
class TerminalStream(
    private val control: RexControl,
    private val sessionId: String,
    private val blockId: String,
    private val scope: CoroutineScope,
    /**
     * Restores the stream's opening Ghostty snapshot into the emulator, returning false if it
     * can't. When set and it succeeds, the snapshot replaces the VT redraw seed, and the output
     * after it continues exactly where the snapshot was cut. Null for emulators that can't read it.
     */
    private val restoreSnapshot: (suspend (ByteArray) -> Boolean)? = null,
) {
    private val _events = MutableSharedFlow<TermEvent>(replay = 0, extraBufferCapacity = 4096)
    val events: SharedFlow<TermEvent> = _events

    private var ws: WebSocket? = null
    private var job: Job? = null
    @Volatile private var stopped = false

    // Resume state
    private var token: ByteArray? = null
    private var nonce: String? = null
    private var bytesReceived = 0L

    // Parse state
    private var skippingSnapshot = true
    private var seedPending = false
    private var carry = ByteArray(0)
    private val snapshot = java.io.ByteArrayOutputStream()
    private var snapshotTooBig = false
    private val heldDuringSeed = ArrayList<ByteArray>()

    fun start() {
        stopped = false
        job = scope.launch { loop() }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        // Status 1000 ends the stream on the server.
        ws?.close(1000, null)
        ws = null
    }

    private suspend fun loop() {
        var fresh = true
        while (!stopped) {
            val end = CompletableDeferred<Pair<Int, String>>()
            try {
                if (fresh || token == null) {
                    _events.emit(TermEvent.Status("connecting…"))
                    val t = control.call("data.connect", buildJsonObject {
                        put("session_id", sessionId); put("block_id", blockId)
                        put("data_ref_id", "terminal"); put("subprotocol", "rex.data.terminal.v1")
                    })!!.jsonObject["ticket"]!!.jsonPrimitive.content
                    val ticket = Base64.decode(t, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    bytesReceived = 0; skippingSnapshot = true; carry = ByteArray(0); nonce = null
                    snapshot.reset(); snapshotTooBig = false
                    open(byteArrayOf(0x01) + ticket, end)
                } else {
                    _events.emit(TermEvent.Status("resuming…"))
                    val b = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                    b.put(0x02); b.put(token!!); b.putLong(bytesReceived)
                    open(b.array(), end)
                }
            } catch (e: Exception) {
                Log.w("TerminalStream", "connect failed", e)
                end.complete(-1 to (e.message ?: "error"))
            }
            val (code, reason) = end.await()
            if (stopped) break
            when {
                code == 4000 -> { _events.emit(TermEvent.Status("block closed")); _events.emit(TermEvent.Closed); return }
                code == 1000 -> { _events.emit(TermEvent.Closed); return }
                // Data handshake rejected / reconnect required -> start over with a new ticket.
                reason.contains("rejected") || code == 1013 || code == 1008 || token == null -> fresh = true
                else -> fresh = false // network drop: try RESUME
            }
            _events.emit(TermEvent.Status("reconnecting…"))
            delay(1000)
        }
    }

    private fun open(handshake: ByteArray, end: CompletableDeferred<Pair<Int, String>>) {
        val req = Request.Builder()
            .url("${control.baseUrl}/api/data")
            .header("Sec-WebSocket-Protocol", "rex.data.terminal.v1")
            .build()
        var gotWelcome = false
        ws = RexControl.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(handshake.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (!gotWelcome) {
                    when (data.firstOrNull()) {
                        0x81.toByte() -> {
                            gotWelcome = true
                            token = data.copyOfRange(1, 17)
                            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            nonce = java.lang.Long.toUnsignedString(bb.getLong(24))
                            _events.tryEmit(TermEvent.Status(null))
                        }
                        0x82.toByte() -> {
                            token = null
                            end.complete(1002 to "rejected ${data.getOrNull(1)}")
                        }
                    }
                    return
                }
                bytesReceived += data.size
                feed(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                end.complete(code to reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                end.complete(-1 to (t.message ?: "failure"))
            }
        })
    }

    // ---- stream parsing -------------------------------------------------------------------

    private fun marker(): ByteArray = "\u001b_rex1;${nonce};".toByteArray()

    private fun feed(chunk: ByteArray) {
        var buf = if (carry.isEmpty()) chunk else carry + chunk
        carry = ByteArray(0)
        val m = marker()
        while (buf.isNotEmpty()) {
            val i = indexOf(buf, m, 0)
            if (i < 0) {
                // Hold back a tail that might be the start of a split marker.
                val keep = partialSuffix(buf, m)
                val emitLen = buf.size - keep
                if (!skippingSnapshot && emitLen > 0) output(buf.copyOfRange(0, emitLen))
                else if (skippingSnapshot) keepSnapshot(buf, emitLen)
                carry = buf.copyOfRange(emitLen, buf.size)
                return
            }
            if (!skippingSnapshot && i > 0) output(buf.copyOfRange(0, i))
            else if (skippingSnapshot) keepSnapshot(buf, i)
            val st = indexOf(buf, byteArrayOf(0x1b, 0x5c), i + m.size) // ESC \
            if (st < 0) { carry = buf.copyOfRange(i, buf.size); return }
            val jsonText = String(buf, i + m.size, st - (i + m.size), Charsets.UTF_8)
            handleRexMessage(jsonText)
            buf = buf.copyOfRange(st + 2, buf.size)
        }
    }

    private fun keepSnapshot(buf: ByteArray, len: Int) {
        if (restoreSnapshot == null || snapshotTooBig || len <= 0) return
        if (snapshot.size() + len > MAX_SNAPSHOT_BYTES) { snapshotTooBig = true; snapshot.reset(); return }
        snapshot.write(buf, 0, len)
    }

    private fun handleRexMessage(text: String) {
        val o = try { RexControl.json.parseToJsonElement(text).jsonObject } catch (e: Exception) { return }
        when (o["type"]?.jsonPrimitive?.content) {
            "resize" -> {
                val ev = TermEvent.Resize(
                    o["cols"]?.jsonPrimitive?.int ?: 80,
                    o["rows"]?.jsonPrimitive?.int ?: 24,
                    o["owner"]?.jsonPrimitive?.content,
                )
                _events.tryEmit(ev)
                if (skippingSnapshot) {
                    skippingSnapshot = false
                    seed(ev)
                }
            }
            "theme" -> _events.tryEmit(TermEvent.Theme(o))
            "child_exited" -> _events.tryEmit(TermEvent.ChildExited(o["exit_code"]?.jsonPrimitive?.intOrNull ?: -1))
        }
    }

    /**
     * Start the emulator from the snapshot if it can read it; otherwise replace the snapshot with
     * a VT redraw of the current screen. Output arriving meanwhile is held and replayed in order.
     */
    private fun seed(size: TermEvent.Resize) {
        seedPending = true
        val snap = snapshot.toByteArray().takeIf { it.size > 10 && String(it, 0, 8, Charsets.ISO_8859_1) == "GHOSTSNP" }
        snapshot.reset()
        scope.launch {
            val restore = restoreSnapshot
            if (snap != null && restore != null && runCatching { restore(snap) }.getOrDefault(false)) {
                synchronized(heldDuringSeed) {
                    _events.tryEmit(size)
                    // The snapshot is exact, so everything after it applies in order.
                    for (b in heldDuringSeed) _events.tryEmit(TermEvent.Output(b))
                    heldDuringSeed.clear()
                    seedPending = false
                }
                return@launch
            }
            val vt = try {
                control.blockCall(sessionId, blockId, "com.superlogical.terminal.format", buildJsonObject {
                    put("format", "vt")
                    put("extra_cursor", true); put("extra_modes", true); put("extra_style", true)
                    put("extra_scrolling_region", true); put("extra_charsets", true)
                    put("extra_keyboard", true); put("extra_tabstops", true)
                })?.jsonObject?.get("content")?.jsonPrimitive?.content
            } catch (e: Exception) { null }
            synchronized(heldDuringSeed) {
                _events.tryEmit(TermEvent.Reset)
                _events.tryEmit(size)
                if (vt != null) _events.tryEmit(TermEvent.Output(vt.toByteArray()))
                // Output that raced the format call is (approximately) already in the seed.
                heldDuringSeed.clear()
                seedPending = false
            }
        }
    }

    private fun output(bytes: ByteArray) {
        synchronized(heldDuringSeed) {
            if (seedPending) { heldDuringSeed.add(bytes); return }
        }
        _events.tryEmit(TermEvent.Output(bytes))
    }

    private companion object { const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024 }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun partialSuffix(h: ByteArray, n: ByteArray): Int {
        val max = minOf(h.size, n.size - 1)
        for (len in max downTo 1) {
            var ok = true
            for (j in 0 until len) if (h[h.size - len + j] != n[j]) { ok = false; break }
            if (ok) return len
        }
        return 0
    }
}
