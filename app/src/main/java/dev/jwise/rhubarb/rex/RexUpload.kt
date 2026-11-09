package dev.jwise.rhubarb.rex

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID

/**
 * Sends files to the machine a terminal runs on (API revision 11, `terminal.upload_begin`).
 *
 * Each file is reserved with `upload_begin`, then its bytes go over their own `/api/data`
 * connection with subprotocol `rex.data.bytes.v1`: HELLO with the ticket, the raw bytes, then an
 * empty message to say "that's all". Rex saves the file and types its path into the pane, as if
 * it had been pasted; the client never types the path itself. A normal close (1000) after the
 * empty message means the file arrived. Closing early cancels it.
 *
 * Files of one call are a batch, so their paths are typed together, in order.
 */
object RexUpload {
    class Item(val uri: Uri, val name: String, val size: Long)

    sealed interface Progress {
        data class Sending(val index: Int, val count: Int, val fraction: Float) : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    const val MAX_BYTES = 100L * 1024 * 1024
    private const val CHUNK = 64 * 1024
    private const val MAX_QUEUED = 1L * 1024 * 1024

    // The data protocol asks for uncompressed frames; OkHttp would otherwise deflate large ones.
    private val http: OkHttpClient by lazy {
        RexControl.http.newBuilder().minWebSocketMessageToCompress(Long.MAX_VALUE).build()
    }

    /** Name and size of a content URI; reads it through if the provider doesn't know the size. */
    fun describe(ctx: Context, uri: Uri, mime: String? = null): Item {
        var name: String? = null
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        if (size < 0) {
            size = ctx.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(CHUNK); var n = 0L
                while (true) { val r = s.read(buf); if (r < 0) break; n += r }
                n
            } ?: throw IllegalArgumentException("Can't read that image")
        }
        val type = mime ?: ctx.contentResolver.getType(uri)
        val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "png"
        val n = name?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "image-${System.currentTimeMillis() / 1000}.$ext"
        return Item(uri, if ('.' in n) n else "$n.$ext", size)
    }

    /**
     * Upload [items] to [blockId]'s machine. Throws [RexError] for server refusals; reports
     * progress through [onProgress]. Cancelling the coroutine cancels the file in flight.
     */
    suspend fun send(
        ctx: Context, control: RexControl, sessionId: String, blockId: String,
        items: List<Item>, onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        items.firstOrNull { it.size > MAX_BYTES }?.let { throw RexError("too_large", "${it.name} is over 100 MB", 413) }
        val batch = if (items.size > 1) UUID.randomUUID().toString() else null
        items.forEachIndexed { i, item ->
            onProgress(Progress.Sending(i, items.size, 0f))
            val res = control.blockCall(sessionId, blockId, "com.superlogical.terminal.upload_begin", buildJsonObject {
                put("ref", "upload:${UUID.randomUUID()}")
                put("name", item.name)
                put("size", item.size)
                if (batch != null) { put("batch", batch); put("index", i); put("count", items.size) }
            })!!.jsonObject
            val ticket = Base64.decode(res["ticket"]!!.jsonPrimitive.content, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            stream(ctx, control.baseUrl, ticket, item) { f -> onProgress(Progress.Sending(i, items.size, f)) }
        }
        onProgress(Progress.Done)
    }

    private suspend fun stream(ctx: Context, baseUrl: String, ticket: ByteArray, item: Item, onFraction: (Float) -> Unit) {
        val welcome = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Pair<Int, String>>()
        val req = Request.Builder().url("$baseUrl/api/data").header("Sec-WebSocket-Protocol", "rex.data.bytes.v1").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send((byteArrayOf(0x01) + ticket).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                when (bytes.size.takeIf { it > 0 }?.let { bytes[0] }) {
                    0x81.toByte() -> welcome.complete(Unit)
                    0x82.toByte() -> welcome.completeExceptionally(RexError("rejected", "Rex refused the upload", 403))
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); closed.complete(code to reason)
                welcome.completeExceptionally(RexError("closed", reason.ifBlank { "connection closed ($code)" }, code))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(-1 to (t.message ?: "network error"))
                welcome.completeExceptionally(RexError("network", t.message ?: "network error", 503))
            }
        })
        var finished = false
        try {
            withTimeout(20_000) { welcome.await() }
            ctx.contentResolver.openInputStream(item.uri)?.use { input ->
                val buf = ByteArray(CHUNK)
                var sent = 0L
                while (sent < item.size) {
                    val r = input.read(buf, 0, minOf(CHUNK.toLong(), item.size - sent).toInt())
                    if (r < 0) break
                    while (ws.queueSize() > MAX_QUEUED) {
                        if (closed.isCompleted) break
                        delay(10)
                    }
                    if (closed.isCompleted || !ws.send(buf.toByteString(0, r))) break
                    sent += r
                    onFraction(((sent - ws.queueSize()).coerceAtLeast(0).toFloat() / item.size).coerceIn(0f, 1f))
                }
                if (sent != item.size) throw RexError("short", "${item.name} changed while sending", 400)
            } ?: throw RexError("unreadable", "Can't read ${item.name}", 400)
            ws.send(ByteString.EMPTY) // half-close: no more bytes
            while (ws.queueSize() > 0 && !closed.isCompleted) {
                onFraction(((item.size - ws.queueSize()).toFloat() / item.size).coerceIn(0f, 1f))
                delay(20)
            }
            onFraction(1f)
            finished = true
            val (code, reason) = withTimeout(60_000) { closed.await() }
            if (code != 1000) throw RexError("failed", when {
                code == -1 -> "Lost the connection while finishing; check the pane before sending again"
                reason.isNotBlank() -> reason
                else -> "Rex closed the upload ($code)"
            }, code)
        } finally {
            if (!finished) ws.close(1000, null) // without the empty message, this cancels the file
        }
    }
}
