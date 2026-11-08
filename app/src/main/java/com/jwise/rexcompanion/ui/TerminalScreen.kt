package com.jwise.rexcompanion.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jwise.rexcompanion.RexApp
import com.jwise.rexcompanion.rex.RexHub
import com.jwise.rexcompanion.rex.TermEvent
import com.jwise.rexcompanion.rex.TerminalStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

private class Bridge(
    val onInput: (String) -> Unit,
    val ready: CompletableDeferred<Unit>,
    val log: (String) -> Unit,
    val tap: () -> Unit,
    val wheel: (lines: Int, col: Int, row: Int) -> Unit,
) {
    @JavascriptInterface fun onData(s: String) = onInput(s)
    @JavascriptInterface fun onTap() = tap()
    @JavascriptInterface fun onWheel(lines: Int, col: Int, row: Int) = wheel(lines, col, row)
    @JavascriptInterface fun onReady() { ready.complete(Unit) }
    @JavascriptInterface fun onLog(s: String) = log(s)
}



@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(sessionId: String, blockId: String, onBack: () -> Unit) {
    val app = RexApp.instance
    val hub by app.hub.collectAsState()
    val h = hub ?: run { Text("Not connected"); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by h.live.collectAsState()
    val sessions by h.sessions.collectAsState()
    val window = sessions.firstOrNull { it.sessionId == sessionId }?.windows?.firstOrNull { w -> w.blocks.any { it.blockId == blockId } }
    val bl = live[blockId]
    val dark = isDark()
    val termColors by app.settings.terminalColors.collectAsState()
    val palette = if (dark) TermDark else TermLight
    val ghostty = remember { com.jwise.rexcompanion.ghostty.GhosttyTerminal(app.scope) }
    // Chosen when the pane opens; switching renderers applies to the next pane opened.
    val useGhostty = remember { app.settings.renderer.value == com.jwise.rexcompanion.Renderer.Ghostty && ghostty.available }
    var canvasGrid by remember { mutableStateOf<IntArray?>(null) } // cols, rows, cellW, cellH at the canvas's font

    var status by remember { mutableStateOf<String?>("connecting…") }
    var ctrl by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var compose by remember { mutableStateOf(false) }
    // Plain holder (not Compose state): assigning state during composition would loop.
    val jumpToLiveRef = remember { arrayOf<() -> Unit>({}) }
    fun jumpToLive() = jumpToLiveRef[0]()
    var zoom by remember { mutableFloatStateOf(1f) }
    var owned by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var gridSize by remember { mutableStateOf("") }

    // Serialize writes: the server runs block methods concurrently, so keystrokes must be
    // sent one call at a time to stay in order.
    // Keys and wheel scrolls share one queue so they reach the program in order.
    val inputs = remember { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    LaunchedEffect(h) {
        for (op in inputs) try { op() } catch (e: Exception) { status = "input failed: ${e.message}" }
    }
    fun send(s: String) { inputs.trySend { h.write(sessionId, blockId, s) } }
    // Wheel deltas that arrive while a send is queued are merged into it, so a fast fling
    // becomes a few larger scrolls instead of a backlog of tiny network calls.
    val wheelPending = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    fun wheel(lines: Int, col: Int, row: Int) {
        if (wheelPending.getAndAdd(lines) != 0) return // a send is already queued; it will carry these lines
        inputs.trySend {
            val n = wheelPending.getAndSet(0)
            if (n == 0) return@trySend
            h.control.call("block.event", buildJsonObject {
                put("session_id", sessionId); put("block_id", blockId)
                put("event", buildJsonObject {
                    put("type", "wheel")
                    put("payload", buildJsonObject {
                        put("x", col); put("y", row); put("delta_y", n); put("delta_mode", "line")
                    })
                })
            })
        }
    }
    fun key(s: String) {
        jumpToLive()
        if (ctrl && s.length == 1 && s[0].isLetter()) { send(((s[0].uppercaseChar().code - 64).toChar()).toString()); ctrl = false }
        else { send(s); ctrl = false }
    }

    val ready = remember { CompletableDeferred<Unit>() }
    val inputView = remember { TermInputView(ctx) { s -> key(s) } }
    var rx by remember { mutableStateOf(0L) }
    var jsLog by remember { mutableStateOf<String?>(null) }
    val webView = remember {
        WebView(ctx).apply {
            // Without explicit MATCH_PARENT, AndroidView gives the WebView WRAP_CONTENT and it
            // collapses to the page's content height, which is 0 for an absolutely positioned page.
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            setBackgroundColor(android.graphics.Color.parseColor("#" + palette.bg))
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) jsLog = "js: ${m.message()}"
                    return true
                }
            }
            addJavascriptInterface(Bridge(
                onInput = { s -> send(s) },
                ready = ready,
                log = { s -> android.util.Log.i("TermJS", s); if (s.startsWith("js error") || s.startsWith("waiting")) jsLog = s },
                tap = { post { if (!compose) inputView.showKeyboard() } },
                wheel = { n, c, r -> wheel(n, c, r) },
            ), "Android")
            loadUrl("file:///android_asset/term/index.html")
        }
    }
    // Calls made before the page is ready are queued, in order, and run once it is.
    val jsQueue = remember { Channel<String>(Channel.UNLIMITED) }
    LaunchedEffect(webView) {
        ready.await()
        for (code in jsQueue) webView.evaluateJavascript(code, null)
    }
    fun js(code: String) { jsQueue.trySend(code) }
    var macTheme by remember { mutableStateOf<String?>(null) }
    // Apply the phone's palette (or the Mac's) whenever the theme or the choice changes.
    LaunchedEffect(dark, termColors, macTheme) {
        if (useGhostty) ghostty.setColors(ghosttyColors(palette, macTheme.takeIf { termColors == com.jwise.rexcompanion.TerminalColors.Mac }))
        if (termColors == com.jwise.rexcompanion.TerminalColors.App || macTheme == null) {
            js("rex.theme(${palette.toJson()})")
            webView.setBackgroundColor(android.graphics.Color.parseColor("#" + palette.bg))
        } else js("rex.theme($macTheme)")
    }
    jumpToLiveRef[0] = { if (useGhostty) ghostty.scrollToBottom() else js("rex.scrollToBottom()") }

    DisposableEffect(blockId) {
        app.visibleBlockId = blockId
        app.notifier.cancel(blockId)
        val stream = TerminalStream(h.control, sessionId, blockId, app.scope)
        val pending = ByteArrayOutputStream()
        // Subscribe before the stream starts: the events flow has no replay, so anything
        // emitted before collection begins (the screen seed!) would be lost.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            launch {
                // Flush batched output ~60x/s so a busy program doesn't flood the JS bridge.
                while (true) {
                    delay(16)
                    val bytes = synchronized(pending) { if (pending.size() == 0) null else pending.toByteArray().also { pending.reset() } }
                    if (bytes != null) js("rex.write('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')")
                }
            }
            stream.events.collect { ev ->
                when (ev) {
                    is TermEvent.Reset -> if (useGhostty) ghostty.reset() else { synchronized(pending) { pending.reset() }; js("rex.reset()") }
                    is TermEvent.Output -> { rx += ev.bytes.size; if (useGhostty) ghostty.write(ev.bytes) else synchronized(pending) { pending.write(ev.bytes) } }
                    is TermEvent.Resize -> { gridSize = "${ev.cols}×${ev.rows}"; if (useGhostty) ghostty.resize(ev.cols, ev.rows) else js("rex.resize(${ev.cols}, ${ev.rows})") }
                    // The Mac's colors arrive in the stream; use them only if the user chose to.
                    is TermEvent.Theme -> { macTheme = ev.json.toString(); if (app.settings.terminalColors.value == com.jwise.rexcompanion.TerminalColors.Mac) js("rex.theme($macTheme)") }
                    is TermEvent.ChildExited -> status = "program exited (${ev.code})"
                    is TermEvent.Status -> status = ev.text
                    TermEvent.Closed -> status = "closed"
                }
            }
        }
        stream.start()
        onDispose {
            job.cancel(); stream.stop(); ghostty.close()
            if (app.visibleBlockId == blockId) app.visibleBlockId = null
            if (owned) app.scope.launch { release(h, sessionId, blockId) }
            webView.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(window?.label ?: "Terminal", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            jsLog,
                            status,
                            statusLabel(bl?.top)?.let { "${bl?.top?.app ?: ""} $it".trim() },
                            bl?.title?.ifBlank { null },
                            gridSize.ifBlank { null },
                        ).joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (bl?.top != null) { StatusDot(bl.top?.state); Spacer(Modifier.width(6.dp)) }
                            Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { zoom = (zoom / 1.15f).coerceAtLeast(0.5f); if (!useGhostty) js("rex.zoom($zoom)") }) { Icon(Icons.Default.ZoomOut, "Smaller") }
                    IconButton(onClick = { zoom = (zoom * 1.15f).coerceAtMost(4f); if (!useGhostty) js("rex.zoom($zoom)") }) { Icon(Icons.Default.ZoomIn, "Larger") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (owned) "Give size back to Mac" else "Fit terminal to phone") },
                            onClick = {
                                menu = false
                                scope.launch {
                                    if (owned) { release(h, sessionId, blockId); owned = false }
                                    else owned = takeOver(h, sessionId, blockId) {
                                        if (useGhostty) canvasGrid?.let { g -> PhoneGrid(g[0], g[1], g[2], g[3]) } else xtermPhoneGrid(webView)
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text("Scroll to bottom") }, onClick = { menu = false; js("rex.scrollToBottom()") })
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().navigationBarsPadding()) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                if (useGhostty) {
                    GhosttyPane(
                        ghostty = ghostty, zoom = zoom, fitToWidth = !owned, background = palette.bg,
                        onTap = { if (!compose) inputView.showKeyboard() },
                        onWheelInput = { bytes -> send(String(bytes, Charsets.UTF_8)) },
                        onCanvasGrid = { canvasGrid = it },
                    )
                } else {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
                // Invisible owner of the soft keyboard; tapping the terminal focuses it.
                AndroidView(factory = { inputView }, modifier = Modifier.size(1.dp))
            }
            KeyBar(
                ctrl, onCtrl = { ctrl = !ctrl }, onKey = ::key,
                compose = compose,
                onKeyboard = { compose = false; inputView.showKeyboard() },
                onCompose = { compose = !compose; if (!compose) inputView.showKeyboard() },
                onPaste = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text ->
                        inputs.trySend {
                            h.control.call("block.event", buildJsonObject {
                                put("session_id", sessionId); put("block_id", blockId)
                                put("event", buildJsonObject { put("type", "paste"); put("payload", buildJsonObject { put("text", text) }) })
                            })
                        }
                    }
                },
            )
            if (compose) {
                // Optional message box: useful for voice typing or autocorrected prose.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Compose a message…") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { key(input + "\r"); input = "" }),
                    )
                    IconButton(onClick = { if (input.isEmpty()) key("\r") else { key(input + "\r"); input = "" } }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

data class PhoneGrid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

/** The phone-sized grid the xterm.js page computes at a readable font size. */
private suspend fun xtermPhoneGrid(webView: WebView): PhoneGrid? = try {
    val grid = CompletableDeferred<String>()
    withContext(Dispatchers.Main) { webView.evaluateJavascript("rex.phoneGrid(12)") { grid.complete(it) } }
    // evaluateJavascript returns a JSON-encoded string literal.
    val g = kotlinx.serialization.json.Json.parseToJsonElement(
        kotlinx.serialization.json.Json.parseToJsonElement(grid.await()).jsonPrimitive.content
    ).jsonObject
    PhoneGrid(g["cols"]!!.jsonPrimitive.int, g["rows"]!!.jsonPrimitive.int, g["cw"]!!.jsonPrimitive.int, g["ch"]!!.jsonPrimitive.int)
} catch (e: Exception) { null }

/** Claim the block so its size follows this phone, then size it to [grid]. */
private suspend fun takeOver(h: RexHub, sessionId: String, blockId: String, grid: suspend () -> PhoneGrid?): Boolean = try {
    val g = grid() ?: throw IllegalStateException("no grid")
    h.control.call("session.own_block", buildJsonObject {
        put("session_id", sessionId); put("block_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(blockId)) }); put("mode", "take")
    })
    h.control.blockCall(sessionId, blockId, "com.superlogical.terminal.resize", buildJsonObject {
        put("columns", g.cols); put("rows", g.rows); put("cell_width_px", g.cellW); put("cell_height_px", g.cellH)
    })
    true
} catch (e: Exception) { false }

private suspend fun release(h: RexHub, sessionId: String, blockId: String) {
    try {
        h.control.call("session.disown_block", buildJsonObject {
            put("session_id", sessionId); put("block_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(blockId)) })
        })
    } catch (_: Exception) {}
}

@Suppress("unused") private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
