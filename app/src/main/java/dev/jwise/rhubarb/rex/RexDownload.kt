package dev.jwise.rhubarb.rex

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Fetches a file from the machine a terminal runs on (API revision 13,
 * `terminal.download_begin`). The file's bytes come over their own `/api/data` connection with
 * subprotocol `rex.data.bytes.v1`: HELLO with the ticket, a WELCOME, then the file in binary
 * messages. A normal close (1000) means the whole file was sent.
 */
object RexDownload {
    const val MIN_REVISION = 13
    const val MAX_BYTES = 1L shl 30 // 1 GiB: it lands in the phone's cache first

    class Downloaded(val file: File, val name: String)

    private val http: OkHttpClient by lazy {
        RexControl.http.newBuilder().minWebSocketMessageToCompress(Long.MAX_VALUE).build()
    }

    /** Where downloads wait until they're saved or shared. Served by the app's FileProvider. */
    fun dir(ctx: Context) = File(ctx.cacheDir, "shared/downloads")

    /**
     * Download [path] (absolute, ~/…, or relative to the pane's working directory). Reports the
     * fraction received through [onProgress]; cancelling the coroutine stops the transfer.
     */
    suspend fun fetch(
        ctx: Context, control: RexControl, sessionId: String, blockId: String, path: String,
        onProgress: (Float) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        if (control.apiRevision in 1 until MIN_REVISION)
            throw RexError("too_old", "This Rex server is too old to send files. Update Rex.", 400)
        val r = try {
            control.blockCall(sessionId, blockId, "com.superlogical.terminal.download_begin", buildJsonObject {
                put("ref", "download:${UUID.randomUUID().toString().lowercase()}")
                put("path", path)
            })!!.jsonObject
        } catch (e: RexError) {
            throw when (e.code) {
                "method_not_found", "revision_too_low" -> RexError(e.code, "This Rex server is too old to send files. Update Rex.", e.status)
                "not_found" -> RexError(e.code, "There's no file at $path", e.status)
                else -> e
            }
        }
        val name = r["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
        val size = r["size"]?.jsonPrimitive?.longOrNull ?: -1L
        if (size > MAX_BYTES) throw RexError("too_large", "$name is over 1 GB", 413)
        val ticket = Base64.decode(r["ticket"]!!.jsonPrimitive.content, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val out = File(dir(ctx).apply { mkdirs() }, safeName(name))
        try {
            receive(ticket, control.baseUrl, out, size, onProgress)
        } catch (e: Throwable) {
            out.delete(); throw e
        }
        Downloaded(out, name)
    }

    private fun safeName(n: String) = n.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]+"), "-").trim().ifBlank { "download" }.take(120)

    private suspend fun receive(ticket: ByteArray, baseUrl: String, out: File, size: Long, onProgress: (Float) -> Unit) {
        val welcome = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val sink = FileOutputStream(out)
        var got = 0L
        var handshaken = false
        val req = Request.Builder().url("$baseUrl/api/data").header("Sec-WebSocket-Protocol", "rex.data.bytes.v1").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send((byteArrayOf(0x01) + ticket).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The first message is the handshake answer; everything after it is the file,
                // even a message that happens to start with 0x81.
                if (!handshaken) {
                    handshaken = true
                    when (bytes.size.takeIf { it > 0 }?.let { bytes[0] }) {
                        0x81.toByte() -> welcome.complete(Unit)
                        else -> welcome.completeExceptionally(RexError("rejected", "Rex refused the download", 403))
                    }
                    return
                }
                try {
                    if (got + bytes.size > MAX_BYTES) throw RexError("too_large", "The file grew past 1 GB", 413)
                    bytes.write(sink); got += bytes.size
                    if (size > 0) onProgress((got.toFloat() / size).coerceIn(0f, 1f))
                } catch (e: Exception) {
                    done.completeExceptionally(e); webSocket.cancel()
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                val err = RexError("closed", reason.ifBlank { "Rex stopped the download ($code)" }, code)
                welcome.completeExceptionally(err)
                if (code == 1000 && handshaken) done.complete(Unit) else done.completeExceptionally(err)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val err = RexError("network", t.message ?: "network error", 503)
                welcome.completeExceptionally(err); done.completeExceptionally(err)
            }
        })
        try {
            withTimeout(20_000) { welcome.await() }
            // No overall timeout: big files on slow links take as long as they take.
            suspendCancellableCoroutine<Unit> { c ->
                c.invokeOnCancellation { ws.cancel() }
                done.invokeOnCompletion { e -> if (e == null) c.resumeWith(Result.success(Unit)) else c.resumeWith(Result.failure(e)) }
            }
            onProgress(1f)
        } catch (e: Throwable) {
            ws.cancel(); throw e
        } finally {
            runCatching { sink.close() }
        }
    }
}
