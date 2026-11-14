package dev.jwise.rhubarb.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddPhotoAlternate
import dev.jwise.rhubarb.rex.RexUpload
import kotlinx.coroutines.Job
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VerticalAlignBottom
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
import dev.jwise.rhubarb.RexApp
import dev.jwise.rhubarb.ghostty.SearchMatch
import dev.jwise.rhubarb.rex.RexHub
import dev.jwise.rhubarb.rex.TermEvent
import dev.jwise.rhubarb.rex.TerminalStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    val ghostty = remember { dev.jwise.rhubarb.ghostty.GhosttyTerminal(app.scope) }
    // Chosen when the pane opens; switching renderers applies to the next pane opened.
    val useGhostty = remember { app.settings.renderer.value == dev.jwise.rhubarb.Renderer.Ghostty && ghostty.available }
    var canvasGrid by remember { mutableStateOf<IntArray?>(null) } // cols, rows, cellW, cellH at the canvas's font

    var status by remember { mutableStateOf<String?>("connecting…") }
    val mods = remember { KeyMods() }
    var input by remember { mutableStateOf("") }
    var compose by remember { mutableStateOf(false) }
    // Plain holder (not Compose state): assigning state during composition would loop.
    val jumpToLiveRef = remember { arrayOf<() -> Unit>({}) }
    fun jumpToLive() = jumpToLiveRef[0]()
    var zoom by remember { mutableFloatStateOf(1f) }
    var owned by remember { mutableStateOf(false) }
    var paneOwner by remember { mutableStateOf<String?>(null) } // from Rex's size messages
    val autoFit by app.settings.fitToPhone.collectAsState()
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
        send(mods.apply(s))
    }

    // Images sent to the Mac (Rex types their paths into this pane when they arrive).
    var upload by remember { mutableStateOf<RexUpload.Progress?>(null) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    fun sendImages(uris: List<Pair<Uri, String?>>) {
        if (uris.isEmpty() || uploadJob?.isActive == true) return
        upload = RexUpload.Progress.Sending(0, uris.size, 0f)
        uploadJob = app.scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { uris.map { (u, mime) -> RexUpload.describe(ctx, u, mime) } }
                RexUpload.send(ctx, h.control, sessionId, blockId, items) { upload = it }
                delay(1500)
                upload = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                upload = null; throw e
            } catch (e: Exception) {
                val code = (e as? dev.jwise.rhubarb.rex.RexError)?.code
                upload = RexUpload.Progress.Failed(when (code) {
                    "method_not_found" -> "This Rex server is too old to receive files; update Rex"
                    "too_large" -> e.message ?: "Too large"
                    else -> e.message ?: "Couldn't send the image"
                })
            }
        }
    }
    fun pasteText(text: String) {
        jumpToLive()
        inputs.trySend {
            h.control.call("block.event", buildJsonObject {
                put("session_id", sessionId); put("block_id", blockId)
                put("event", buildJsonObject { put("type", "paste"); put("payload", buildJsonObject { put("text", text) }) })
            })
        }
    }

    // Text or images shared from another app to this pane: pasted (not sent), or uploaded.
    val shareTick by ShareInbox.deliveries.collectAsState()
    LaunchedEffect(shareTick) {
        val share = ShareInbox.take(blockId) ?: return@LaunchedEffect
        share.text?.let(::pasteText)
        if (share.images.isNotEmpty()) sendImages(share.images)
    }

    // Whatever the pane's program reports while it's open counts as seen (for Home's board).
    LaunchedEffect(bl?.top?.updatedAt) { app.settings.markSeen(blockId, bl?.top?.updatedAt) }

    // Events from this pane's program: clipboard copies (OSC 52) and the bell.
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(h) {
        h.blockEvents.collect { ev ->
            if (ev.blockId != blockId || !app.foreground) return@collect
            when (ev.name) {
                "clipboard_written" -> {
                    if (!app.settings.programClipboard.value.value) return@collect
                    val p = ev.payload
                    val text = p?.get("text")?.jsonPrimitive?.contentOrNull ?: run {
                        val bytes = p?.get("bytes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        if (bytes <= 0) return@collect
                        runCatching { h.clipboardText(sessionId, blockId, p?.get("id")?.jsonPrimitive?.content?.toLongOrNull()) }.getOrNull()
                    } ?: return@collect
                    if (text.isEmpty()) return@collect
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
                    // Android 13 and later show their own "copied" confirmation.
                    if (android.os.Build.VERSION.SDK_INT < 33)
                        android.widget.Toast.makeText(ctx, "Copied from the terminal", android.widget.Toast.LENGTH_SHORT).show()
                }
                "bell" -> if (app.settings.bellVibrate.value.value)
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    // At a password prompt (sudo, ssh) the keyboard becomes a password field. Rex has no event
    // for this, so ask every second or so while the pane is open.
    var passwordPrompt by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    // Find in scrollback (Ghostty renderer): matches oldest first; the newest is shown first.
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<SearchMatch>()) }
    var matchIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(finding, findQuery) {
        if (!finding || findQuery.isEmpty()) { matches = emptyList(); matchIndex = -1; return@LaunchedEffect }
        delay(150)
        val found = ghostty.search(findQuery)
        matches = found; matchIndex = found.lastIndex
        found.lastOrNull()?.let(ghostty::reveal)
    }
    fun stepMatch(d: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + d).mod(matches.size)
        ghostty.reveal(matches[matchIndex])
    }
    fun closeFind() { finding = false; findQuery = ""; ghostty.scrollToBottom() }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        sendImages(uris.map { it to null })
    }
    // Any file, sent to the pane's machine the same way as images (Rex types its path).
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        sendImages(uris.map { it to null })
    }
    // Android's own speech-to-text: it records, Rhubarb only gets the words, which land in the box.
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { t ->
            input = if (input.isBlank()) t else input.trimEnd() + " " + t
        }
    }

    val ready = remember { CompletableDeferred<Unit>() }
    val inputView = remember { TermInputView(ctx, send = { s -> key(s) }, onImage = { u, mime -> sendImages(listOf(u to mime)) }) }
    LaunchedEffect(h) {
        while (true) {
            if (app.foreground) {
                val p = h.atPasswordPrompt(sessionId, blockId) == true
                if (p != passwordPrompt) { passwordPrompt = p; inputView.secret = p }
            }
            delay(1200)
        }
    }

    var rx by remember { mutableStateOf(0L) }
    var jsLog by remember { mutableStateOf<String?>(null) }

    // Claude Code panes: read the bottom of the screen after output settles, for the answer card,
    // the controls strip and the message box.
    val isClaude = programOf(bl).name == "Claude Code"
    val answerOn by app.settings.answerButtons.value.collectAsState()
    val stripOn by app.settings.claudeBar.value.collectAsState()
    var claude by remember { mutableStateOf<dev.jwise.rhubarb.agent.ClaudeScreen?>(null) }
    var readTick by remember { mutableStateOf(0) }
    LaunchedEffect(isClaude) {
        if (!isClaude) { claude = null; return@LaunchedEffect }
        var seenRx = -1L; var seenTick = -1; var last = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (app.foreground && (rx != seenRx || readTick != seenTick || now - last > 5000)) {
                seenRx = rx; seenTick = readTick; last = now
                dev.jwise.rhubarb.agent.PromptAnswers.read(h, sessionId, blockId)?.let { claude = it }
            }
            delay(450)
        }
    }
    // The message box is the easiest way to talk to an agent, so it starts open.
    LaunchedEffect(isClaude) { if (isClaude) compose = true }
    var answering by remember { mutableStateOf(false) }
    fun answer(p: dev.jwise.rhubarb.agent.MenuPrompt, o: dev.jwise.rhubarb.agent.MenuOption) {
        answering = true
        scope.launch {
            val r = dev.jwise.rhubarb.agent.PromptAnswers.answer(h, sessionId, blockId, p, o.number)
            answering = false; readTick++
            when (r) {
                dev.jwise.rhubarb.agent.PromptAnswers.Result.CHANGED -> "Nothing pressed: the question changed"
                dev.jwise.rhubarb.agent.PromptAnswers.Result.UNREACHABLE -> "Nothing pressed: couldn't reach Rex"
                else -> null
            }?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    /** Send the message box: paste it, then Enter. Empty: Enter, or Esc to stop Claude while it works. */
    fun sendMessage() {
        if (input.isNotEmpty()) {
            if (claude?.prompt != null) {
                android.widget.Toast.makeText(ctx, "Answer the question first", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val text = input; input = ""
            pasteText(text)
            inputs.trySend { delay(150); h.write(sessionId, blockId, "\r") }
        } else if (claude?.working == true) send("\u001b") else key("\r")
    }
    fun command(c: String) { jumpToLive(); send(c); inputs.trySend { delay(120); h.write(sessionId, blockId, "\r") }; readTick++ }
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
            // Only the xterm.js renderer uses the page; with Ghostty it would sit unsized and log.
            if (!useGhostty) loadUrl("file:///android_asset/term/index.html")
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
        if (useGhostty) ghostty.setColors(ghosttyColors(palette, macTheme.takeIf { termColors == dev.jwise.rhubarb.TerminalColors.Mac }))
        if (termColors == dev.jwise.rhubarb.TerminalColors.App || macTheme == null) {
            js("rex.theme(${palette.toJson()})")
            webView.setBackgroundColor(android.graphics.Color.parseColor("#" + palette.bg))
        } else js("rex.theme($macTheme)")
    }
    jumpToLiveRef[0] = { if (useGhostty) ghostty.scrollToBottom() else js("rex.scrollToBottom()") }

    DisposableEffect(blockId) {
        app.visibleBlockId = blockId
        app.notifier.cancel(blockId)
        val stream = TerminalStream(h.control, sessionId, blockId, app.scope, if (useGhostty) ghostty::restore else null)
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
                    is TermEvent.Resize -> { gridSize = "${ev.cols}×${ev.rows}"; paneOwner = ev.owner; if (useGhostty) ghostty.resize(ev.cols, ev.rows) else js("rex.resize(${ev.cols}, ${ev.rows})") }
                    // The Mac's colors arrive in the stream; use them only if the user chose to.
                    is TermEvent.Theme -> { macTheme = ev.json.toString(); if (app.settings.terminalColors.value == dev.jwise.rhubarb.TerminalColors.Mac) js("rex.theme($macTheme)") }
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

    suspend fun phoneGrid(): PhoneGrid? =
        if (useGhostty) canvasGrid?.let { g -> PhoneGrid(g[0], g[1], g[2], g[3]) } else xtermPhoneGrid(webView)

    // Fit to phone on open (and again when the canvas changes size, e.g. the keyboard opens),
    // unless turned off in Settings.
    // If another device (the Mac) takes the pane while the phone has it, hand it over: forget
    // the saved size (the Mac sets its own now) and go back to the pane list.
    val conn by h.control.state.collectAsState()
    val myId = (conn as? dev.jwise.rhubarb.rex.ConnState.Connected)?.clientId
    LaunchedEffect(paneOwner, owned, myId) {
        val other = paneOwner
        if (!owned || myId.isNullOrEmpty() || other.isNullOrEmpty() || other == myId) return@LaunchedEffect
        owned = false
        forgetMacSize(blockId)
        android.widget.Toast.makeText(ctx, "Another device took this pane", android.widget.Toast.LENGTH_SHORT).show()
        onBack()
    }

    LaunchedEffect(canvasGrid?.toList(), autoFit) {
        if (!autoFit) return@LaunchedEffect
        delay(300)
        repeat(10) {
            if (takeOver(h, sessionId, blockId) { phoneGrid() }) { owned = true; return@LaunchedEffect }
            delay(500)
        }
    }

    // The pane runs edge to edge: the bars take the terminal's own background.
    val termBg = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor("#" + palette.bg))
    val termFg = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor("#" + palette.fg))
    Scaffold(
        containerColor = termBg,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = termBg, scrolledContainerColor = termBg, titleContentColor = termFg,
                    navigationIconContentColor = termFg, actionIconContentColor = termFg,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        val paneLabel = window?.blocks?.takeIf { it.size > 1 }?.firstOrNull { it.blockId == blockId }?.label
                        Text(listOfNotNull(window?.label ?: "Terminal", paneLabel).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            jsLog.takeIf { !useGhostty },
                            status,
                            "password prompt".takeIf { passwordPrompt },
                            (conn as? dev.jwise.rhubarb.rex.ConnState.Failed)?.reason,
                            statusLabel(bl?.top)?.let { "${bl?.top?.app ?: ""} $it".trim() },
                            bl?.title?.ifBlank { null },
                        ).joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (bl?.top != null) { StatusDot(bl.top?.state); Spacer(Modifier.width(6.dp)) }
                            Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = termFg.copy(alpha = 0.7f))
                        }
                    }
                },
                actions = {
                    if (!owned) IconButton(onClick = { zoom = (zoom / 1.15f).coerceAtLeast(0.5f); if (!useGhostty) js("rex.zoom($zoom)") }) { Icon(Icons.Default.ZoomOut, "Smaller") }
                    if (!owned) IconButton(onClick = { zoom = (zoom * 1.15f).coerceAtMost(4f); if (!useGhostty) js("rex.zoom($zoom)") }) { Icon(Icons.Default.ZoomIn, "Larger") }
                    var paneMenu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { paneMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = paneMenu, onDismissRequest = { paneMenu = false }) {
                            fun run(what: String, op: suspend () -> Unit) {
                                paneMenu = false
                                scope.launch {
                                    try { op() } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "Couldn't $what: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            if (useGhostty) DropdownMenuItem(text = { Text("Find") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                                onClick = { paneMenu = false; compose = false; finding = true })
                            DropdownMenuItem(text = { Text("Send an image") }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                                onClick = { paneMenu = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            if (h.control.apiRevision >= dev.jwise.rhubarb.rex.RexDownload.MIN_REVISION)
                                DropdownMenuItem(text = { Text("Download a file") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                                    onClick = { paneMenu = false; downloading = true })
                            DropdownMenuItem(text = { Text("Share output") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { run("share it") { sharePaneText(ctx, h, sessionId, blockId, window?.label ?: "Terminal") } })
                            DropdownMenuItem(text = { Text("Clear screen") }, leadingIcon = { Icon(Icons.Default.ClearAll, null) },
                                onClick = { run("clear it") { h.clearPane(sessionId, blockId) } })
                            DropdownMenuItem(text = { Text("Reset terminal") }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                onClick = { run("reset it") { h.resetPane(sessionId, blockId) } })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().navigationBarsPadding()) {
            // OSC 9;4 progress from the program, under the title bar.
            bl?.progress?.let { pct ->
                val color = when {
                    bl.progressError -> MaterialTheme.colorScheme.error
                    bl.progressPaused -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                }
                if (pct < 0) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = color)
                else androidx.compose.material3.LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth(), color = color)
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                if (useGhostty) {
                    GhosttyPane(
                        ghostty = ghostty, zoom = zoom, fitToWidth = !owned, background = palette.bg,
                        onTap = { if (!compose && !finding) inputView.showKeyboard() },
                        searchMatches = matches,
                        currentMatch = matches.getOrNull(matchIndex),
                        onWheelInput = { bytes -> send(String(bytes, Charsets.UTF_8)) },
                        onCanvasGrid = { canvasGrid = it },
                    )
                } else {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
                // Invisible owner of the soft keyboard; tapping the terminal focuses it.
                AndroidView(factory = { inputView }, modifier = Modifier.size(1.dp))
                upload?.let { p ->
                    UploadPill(p, Modifier.align(Alignment.BottomStart).padding(12.dp),
                        onCancel = { uploadJob?.cancel(); upload = null })
                }
                val snap by ghostty.snapshot.collectAsState()
                androidx.compose.animation.AnimatedVisibility(
                    visible = useGhostty && snap?.viewportAtBottom == false,
                    enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    androidx.compose.material3.SmallFloatingActionButton(onClick = { jumpToLive() }) {
                        Icon(Icons.Default.ArrowDownward, "Jump to latest")
                    }
                }
            }
            if (!finding && isClaude) {
                claude?.prompt?.takeIf { answerOn && it.answers.isNotEmpty() }?.let { p ->
                    AnswerCard(p, answering) { o -> answer(p, o) }
                }
                if (stripOn) ClaudeStrip(claude, onKeys = { k -> jumpToLive(); send(k); scope.launch { delay(400); readTick++ } }, onCommand = ::command)
            }
            if (finding) FindBar(
                query = findQuery, onQuery = { findQuery = it },
                count = matches.size, position = if (matchIndex < 0) 0 else matches.size - matchIndex,
                onOlder = { stepMatch(-1) }, onNewer = { stepMatch(1) }, onClose = ::closeFind,
            ) else KeyBar(
                mods, onKey = ::key,
                compose = compose,
                onKeyboard = { compose = false; inputView.showKeyboard() },
                onCompose = { compose = !compose; if (!compose) inputView.showKeyboard() },
                onPaste = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    val imageUri = clip?.getItemAt(0)?.uri?.takeIf { clip.description.hasMimeType("image/*") }
                    if (imageUri != null) {
                        sendImages(listOf(imageUri to clip.description.getMimeType(0)))
                    } else clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let(::pasteText)
                },
            )
            if (compose) {
                // Optional message box: useful for voice typing or autocorrected prose.
                // One wide pill, like the Claude and Messages apps: + to attach, then the text,
                // then Enter/Send/Stop and the mic inside it.
                var attachMenu by remember { mutableStateOf(false) }
                val stop = input.isEmpty() && claude?.working == true
                androidx.compose.material3.TextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                    placeholder = { Text(if (isClaude) "Message Claude" else "Message") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    leadingIcon = {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, "Attach") }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                                    onClick = { attachMenu = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                                    onClick = { attachMenu = false; pickFiles.launch(arrayOf("*/*")) })
                            }
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = ::sendMessage) {
                                when {
                                    input.isNotEmpty() -> Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                                    stop -> Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                                    else -> Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Press Enter")
                                }
                            }
                            IconButton(onClick = {
                                val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, if (isClaude) "Message Claude" else "Message")
                                try { dictate.launch(i) } catch (e: android.content.ActivityNotFoundException) {
                                    android.widget.Toast.makeText(ctx, "No speech recognizer on this phone", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Icon(Icons.Default.Mic, "Dictate") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                )
            }
        }
    }
    if (downloading) DownloadSheet(h, sessionId, blockId, bl?.pwd, onDismiss = { downloading = false })
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

/**
 * The size each pane had before the phone took it over. Rex.app doesn't own its panes; it sets a
 * size only when its window lays out, so a pane the phone lets go of would otherwise keep the
 * phone's size. Kept for the app's lifetime so a quick reopen doesn't record the phone's size.
 */
private val macSizes = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

private fun forgetMacSize(blockId: String) { macSizes.remove(blockId) }

/** Claim the block so its size follows this phone, then size it to [grid]. */
private suspend fun takeOver(h: RexHub, sessionId: String, blockId: String, grid: suspend () -> PhoneGrid?): Boolean = try {
    val g = grid() ?: throw IllegalStateException("no grid")
    if (!macSizes.containsKey(blockId)) {
        runCatching {
            val cur = h.control.blockCall(sessionId, blockId, "com.superlogical.terminal.size")?.jsonObject
            if (cur != null) macSizes[blockId] = buildJsonObject {
                for (k in listOf("columns", "rows", "cell_width_px", "cell_height_px")) cur[k]?.let { put(k, it) }
            }
        }
    }
    h.control.call("session.own_block", buildJsonObject {
        put("session_id", sessionId); put("block_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(blockId)) }); put("mode", "take")
    })
    h.control.blockCall(sessionId, blockId, "com.superlogical.terminal.resize", buildJsonObject {
        put("columns", g.cols); put("rows", g.rows); put("cell_width_px", g.cellW); put("cell_height_px", g.cellH)
    })
    true
} catch (e: Exception) { false }

/** Give up the block and put back the size it had before [takeOver] (applies at once with no owner). */
private suspend fun release(h: RexHub, sessionId: String, blockId: String) {
    try {
        h.control.call("session.disown_block", buildJsonObject {
            put("session_id", sessionId); put("block_ids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(blockId)) })
        })
        macSizes[blockId]?.let { size ->
            h.control.blockCall(sessionId, blockId, "com.superlogical.terminal.resize", size)
            macSizes.remove(blockId)
        }
    } catch (_: Exception) {}
}

@Suppress("unused") private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
