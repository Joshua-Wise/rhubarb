package dev.jwise.rhubarb.rex

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The demo: a pretend Mac running Rex inside the app, for trying Rhubarb (and app review) without a server. */
object Demo {
    /** The server address the demo uses. `.invalid` is reserved, so it can never reach a real host. */
    const val URL = "wss://demo.rhubarb.invalid"
    fun isDemo(base: String?): Boolean = base?.contains("demo.rhubarb.invalid") == true
}

/**
 * A small in-process stand-in for a Rex server, answering the same control and data protocols the
 * real one does, so every screen works unchanged. Made-up sessions: Claude Code asking to run tests
 * (with a teammate in the same tab), Codex refactoring, a finished Claude Code session, a failed
 * deploy and a shell. Answers, Esc, messages and chat view all respond.
 */
internal class DemoServer private constructor() : WebSocket.Factory {
    companion object { val instance by lazy { DemoServer() } }

    // One thread, so requests are handled in order; a mistake here is logged, never fatal.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("DemoServer", "demo failed", e) })
    private val ids = AtomicInteger(1)
    private fun now() = Instant.now().toString()
    private fun ago(sec: Long) = Instant.now().minusSeconds(sec).toString()

    // ---- the pretend Mac -------------------------------------------------------------------

    private enum class Kind { CLAUDE, CODEX, SHELL, HELPER }
    private enum class Phase { ASKING, RUNNING, IDLE, WORKING }

    private inner class Pane(
        val id: String, var label: String, val kind: Kind, val cwd: String,
        var title: String, var lines: List<String>,
        var state: String? = null, var kind7501: String? = null, var app: String? = null, var msg: String? = null,
        var progress: Int? = null, var updated: String = now(),
    ) {
        var phase = Phase.IDLE
        var typed = StringBuilder()
        var cols = 80; var rows = 30
        val chat = ArrayList<String>()
        /** For a chat helper: the pane it follows, and whether it was told to start. */
        var follows: Pane? = null
        var started = false
    }
    private inner class Win(val id: String, var label: String, val panes: MutableList<Pane>)
    private inner class Sess(val id: String, var label: String, val windows: MutableList<Win>, var revision: Long = 1)

    private val sessions = ArrayList<Sess>()
    private val helpers = ArrayList<Pane>()
    private val streams = HashMap<String, MutableList<FakeSocket>>()

    private fun pid(p: String) = "block:demo-$p"

    private fun claudeIdle(width: Int = 44) = listOf(
        "─".repeat(width),
        "❯ ",
        "─".repeat(width),
        "  ? for shortcuts",
    )

    init { build() }

    private fun build() {
        val api = Pane(pid("api"), "claude", Kind.CLAUDE, "/Users/demo/shop-api", "✳ Login timeout test", emptyList(),
            "blocked", "permission", "claude-code", "Bash: npm test -- auth", updated = ago(40))
        api.phase = Phase.ASKING
        api.lines = apiAsking()
        api.chat += listOf(
            user("api-1", "Add a test for the login timeout, then run the auth tests."),
            text("api-2", "I'll add a test for the session timeout and run the auth suite."),
            tool("api-3", "Edit", """{"file_path":"src/auth/session.test.ts"}"""),
            result("api-3", "Added 18 lines"),
        )
        val mate = Pane(pid("api-tests"), "tests", Kind.CLAUDE, "/Users/demo/shop-api", "✳ Token refresh tests",
            listOf("❯ Write tests for token refresh", "", "⏺ Writing tests for the refresh flow…", "", "✻ Writing tests… (esc to interrupt)") + claudeIdle(),
            "working", null, "claude-code", "Writing tests for token refresh", updated = ago(15))
        mate.phase = Phase.WORKING
        val web = Pane(pid("web"), "codex", Kind.CODEX, "/Users/demo/shop-web", "Checkout refactor", codexWorking(),
            "working", null, "codex", "Refactoring the checkout form", progress = 60, updated = ago(25))
        web.phase = Phase.WORKING
        web.chat += listOf(
            user("web-1", "Split the checkout form into smaller components."),
            text("web-2", "I'll pull the address, payment and summary sections into their own components."),
            tool("web-3", "Bash", """{"command":"npm run lint"}"""),
            result("web-3", "✔ No problems"),
        )
        val docs = Pane(pid("docs"), "claude", Kind.CLAUDE, "/Users/demo/shop-docs", "✳ README refresh", docsDone(),
            "done", null, "claude-code", "Updated the README and the setup guide", updated = ago(300))
        docs.chat += listOf(
            user("docs-1", "Refresh the README for the new setup steps."),
            text("docs-2", "Updated **README.md** and **docs/setup.md**:\n\n- Node 22 is now required\n- `npm run dev` starts the API and the web app together\n- Added a troubleshooting section"),
            tool("docs-3", "Edit", """{"file_path":"README.md"}"""),
            result("docs-3", "Updated 24 lines"),
        )
        val deploy = Pane(pid("deploy"), "deploy", Kind.SHELL, "/Users/demo/shop-api", "deploy",
            listOf("demo@mac shop-api % ./deploy.sh staging", "▸ Building…  done", "▸ Uploading…  done",
                "✖ Deploy failed: STRIPE_KEY isn't set", "demo@mac shop-api % "),
            "error", null, "deploy", "Deploy failed: STRIPE_KEY isn't set", updated = ago(120))
        val zsh = Pane(pid("shell"), "zsh", Kind.SHELL, "/Users/demo", "~", listOf("demo@mac ~ % "))

        sessions += Sess("session:demo-shop", "Shop", mutableListOf(
            Win("window:demo-api", "API", mutableListOf(api, mate)),
            Win("window:demo-web", "Web", mutableListOf(web)),
            Win("window:demo-deploy", "Deploy", mutableListOf(deploy)),
        ))
        sessions += Sess("session:demo-docs", "Docs", mutableListOf(
            Win("window:demo-docs", "Docs", mutableListOf(docs)),
            Win("window:demo-shell", "Shell", mutableListOf(zsh)),
        ))
        // Codex finishes its refactor a little while after the demo opens.
        scope.launch {
            delay(45_000)
            if (web.phase == Phase.WORKING) finishCodex(web)
        }
    }

    private fun apiAsking() = listOf(
        "❯ Add a test for the login timeout, then run",
        "  the auth tests.",
        "",
        "⏺ I'll add a test for the session timeout and",
        "  run the auth suite.",
        "",
        "⏺ Update(src/auth/session.test.ts)",
        "  ⎿  Added 18 lines",
        "",
        "────────────────────────────────────────────",
        " Bash command",
        "",
        "   npm test -- auth",
        "   Run the auth test suite",
        "",
        " Do you want to proceed?",
        " ❯ 1. Yes",
        "   2. Yes, and don't ask again for npm test",
        "      commands in this project",
        "   3. No, and tell Claude what to do",
        "      differently (esc)",
    )

    private fun codexWorking() = listOf(
        "› Split the checkout form into smaller",
        "  components.",
        "",
        "• I'll pull the address, payment and summary",
        "  sections into their own components.",
        "",
        "• Ran npm run lint",
        "  └ ✔ No problems",
        "",
        "• Working (38s • esc to interrupt)",
        "",
        "› Ask Codex to do anything",
        "  GPT-6-Luna medium · ~/shop-web",
    )

    private fun docsDone() = listOf(
        "❯ Refresh the README for the new setup steps.",
        "",
        "⏺ Updated README.md and docs/setup.md:",
        "  - Node 22 is now required",
        "  - npm run dev starts the API and the web",
        "    app together",
        "  - Added a troubleshooting section",
        "",
    ) + claudeIdle()

    // Chat lines in the compact format the chat view reads.
    private fun user(u: String, s: String) = buildJsonObject { put("t", "user"); put("u", u); put("s", s) }.toString()
    private fun text(u: String, x: String) = buildJsonObject {
        put("t", "assistant"); put("u", u)
        put("p", buildJsonArray { add(buildJsonObject { put("t", "text"); put("x", x) }) })
    }.toString()
    private fun tool(id: String, n: String, input: String) = buildJsonObject {
        put("t", "assistant"); put("u", "$id.call")
        put("p", buildJsonArray { add(buildJsonObject { put("t", "tool"); put("id", id); put("n", n); put("i", input) }) })
    }.toString()
    private fun result(id: String, x: String, err: Boolean = false) = buildJsonObject {
        put("t", "user"); put("u", "$id.r")
        put("p", buildJsonArray { add(buildJsonObject { put("t", "result"); put("id", id); put("x", x); put("e", if (err) 1 else 0) }) })
    }.toString()

    private fun allPanes() = sessions.flatMap { s -> s.windows.flatMap { it.panes } }
    private fun pane(id: String?) = allPanes().firstOrNull { it.id == id } ?: helpers.firstOrNull { it.id == id }
    private fun sessionOf(p: Pane) = sessions.firstOrNull { s -> s.windows.any { w -> p in w.panes } }

    // ---- control protocol ------------------------------------------------------------------

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val data = request.url.encodedPath.endsWith("/api/data")
        val ws = FakeSocket(request, listener, data)
        scope.launch {
            val r = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
            listener.onOpen(ws, r)
        }
        return ws
    }

    private inner class FakeSocket(val req: Request, val listener: WebSocketListener, val data: Boolean) : WebSocket {
        @Volatile var open = true
        var block: String? = null
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (!open) return false
            scope.launch { onText(this@FakeSocket, text) }
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            if (!open) return false
            scope.launch { onBytes(this@FakeSocket, bytes.toByteArray()) }
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            if (!open) return false
            open = false
            scope.launch { block?.let { streams[it]?.remove(this@FakeSocket) }; listener.onClosed(this@FakeSocket, code, reason ?: "") }
            return true
        }
        override fun cancel() { close(1000, null) }
        /** The server ends the stream (as when a block closes). */
        fun serverClose(code: Int, reason: String) {
            if (!open) return
            open = false
            listener.onClosing(this, code, reason)
            listener.onClosed(this, code, reason)
        }
        fun text(s: String) { if (open) listener.onMessage(this, s) }
        fun bytes(b: ByteArray) { if (open) listener.onMessage(this, b.toByteString()) }
    }

    private val controls = ArrayList<FakeSocket>()

    private fun onText(ws: FakeSocket, text: String) {
        val msg = runCatching { RexControl.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (msg["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> {
                controls += ws
                ws.text(buildJsonObject {
                    put("type", "welcome"); put("api_revision", RexControl.API_REVISION)
                    put("client", buildJsonObject { put("id", "client:demo-${ids.getAndIncrement()}"); put("reconnect_token", "demo") })
                }.toString())
            }
            "request" -> {
                val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return
                val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: ""
                val payload = msg["payload"] as? JsonObject ?: JsonObject(emptyMap())
                val reply = try {
                    val out = handle(method, payload)
                    buildJsonObject { put("type", "response"); put("id", id); put("payload", out ?: JsonObject(emptyMap())) }
                } catch (e: Exception) {
                    buildJsonObject {
                        put("type", "response"); put("id", id)
                        put("error", buildJsonObject { put("code", "unavailable"); put("message", e.message ?: "Not available in the demo"); put("status", 404) })
                    }
                }
                ws.text(reply.toString())
            }
        }
    }

    private fun event(sessionId: String, inner: JsonObject) {
        val ev = buildJsonObject {
            put("type", "event")
            put("payload", buildJsonObject {
                put("type", "session_event")
                put("payload", buildJsonObject { put("session_id", sessionId); put("event", inner) })
            })
        }.toString()
        controls.removeAll { !it.open }
        controls.forEach { it.text(ev) }
    }

    private fun statusChanged(p: Pane) {
        val s = sessionOf(p) ?: return
        p.updated = now()
        event(s.id, buildJsonObject {
            put("type", "block_event")
            put("payload", buildJsonObject {
                put("block_id", p.id); put("name", "program_status_changed")
                put("payload", buildJsonObject { put("id", "demo"); put("record", record(p)) })
            })
        })
    }

    private fun viewChanged(s: Sess) {
        s.revision++
        event(s.id, buildJsonObject {
            put("type", "session_view_changed"); put("payload", buildJsonObject { put("revision", s.revision) })
        })
    }

    private fun record(p: Pane) = buildJsonObject {
        put("id", "demo"); put("state", p.state ?: "idle")
        p.kind7501?.let { put("kind", it) }; p.app?.let { put("app", it) }; p.msg?.let { put("msg", it) }
        p.progress?.let { put("progress", it) }
        put("updated_at", p.updated)
    }

    private fun view(s: Sess) = buildJsonObject {
        put("session_id", s.id); put("label", s.label); put("revision", s.revision)
        put("active_window_id", s.windows.firstOrNull()?.id ?: "")
        put("windows", buildJsonArray {
            for (w in s.windows) add(buildJsonObject {
                put("window_id", w.id); put("label", w.label); put("focused_block_id", w.panes.firstOrNull()?.id ?: "")
                put("layers", buildJsonArray {
                    add(buildJsonObject {
                        put("blocks", buildJsonArray {
                            for (p in w.panes) add(buildJsonObject { put("block_id", p.id); put("label", p.label); put("creator_name", TERMINAL) })
                        })
                    })
                })
            })
        })
    }

    private fun sess(payload: JsonObject) = payload["session_id"]?.jsonPrimitive?.contentOrNull.let { id ->
        sessions.firstOrNull { it.id == id } ?: throw IllegalStateException("No such session")
    }

    private fun handle(method: String, payload: JsonObject): JsonElement? {
        fun str(k: String) = payload[k]?.jsonPrimitive?.contentOrNull
        if (method.startsWith("$TERMINAL.")) return blockMethod(method.removePrefix("$TERMINAL."), str("block_id"), payload["args"] as? JsonObject ?: JsonObject(emptyMap()))
        return when (method) {
            "session.attach_all", "client.set_label", "session.own_block", "session.disown_block" -> JsonObject(emptyMap())
            // A paste (the message box sends text this way, then Enter).
            "block.event" -> {
                val ev = payload["event"] as? JsonObject
                if (ev?.get("type")?.jsonPrimitive?.contentOrNull == "paste") {
                    val text = (ev["payload"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    pane(str("block_id"))?.let { input(it, text.replace("\n", " ")) }
                }
                JsonObject(emptyMap())
            }
            "session.list" -> buildJsonObject {
                put("sessions", buildJsonArray { sessions.forEach { s -> add(buildJsonObject { put("session_id", s.id); put("label", s.label) }) } })
            }
            "session.attach", "session.view" -> buildJsonObject { put("view", view(sess(payload))) }
            "session.list_blocks" -> buildJsonObject {
                val s = sess(payload)
                put("blocks", buildJsonArray {
                    s.windows.forEach { w -> w.panes.forEach { p -> add(buildJsonObject { put("block_id", p.id); put("label", p.label); put("placement", "placed"); put("window_id", w.id) }) } }
                    helpers.filter { it.follows?.let(::sessionOf) == s }.forEach { h -> add(buildJsonObject { put("block_id", h.id); put("label", h.label); put("placement", "detached") }) }
                })
            }
            "session.new_block" -> newHelper(payload)
            "block.close" -> {
                val id = str("block_id")
                helpers.removeAll { it.id == id }
                streams.remove(id)?.toList()?.forEach { it.serverClose(4000, "block closed") }
                JsonObject(emptyMap())
            }
            "session.set_label" -> { val s = sess(payload); s.label = str("label")?.ifBlank { null } ?: s.label; viewChanged(s); JsonObject(emptyMap()) }
            "session.set_window_label" -> {
                val s = sess(payload); s.windows.firstOrNull { it.id == str("window_id") }?.let { it.label = str("label")?.ifBlank { null } ?: it.label }
                viewChanged(s); JsonObject(emptyMap())
            }
            "session.set_block_label" -> {
                val s = sess(payload); pane(str("block_id"))?.let { it.label = str("label")?.ifBlank { null } ?: it.label }
                viewChanged(s); JsonObject(emptyMap())
            }
            "session.close_window" -> { val s = sess(payload); s.windows.removeAll { it.id == str("window_id") }; viewChanged(s); JsonObject(emptyMap()) }
            "session.move_window" -> {
                val s = sess(payload); val w = s.windows.firstOrNull { it.id == str("window_id") } ?: return JsonObject(emptyMap())
                s.windows.remove(w)
                val i = s.windows.indexOfFirst { it.id == str("before_window_id") }
                if (i < 0) s.windows.add(w) else s.windows.add(i, w)
                viewChanged(s); JsonObject(emptyMap())
            }
            "session.move" -> {
                val s = sess(payload); sessions.remove(s)
                val i = sessions.indexOfFirst { it.id == str("before_session_id") }
                if (i < 0) sessions.add(s) else sessions.add(i, s)
                JsonObject(emptyMap())
            }
            "session.new_window" -> {
                val s = sess(payload); val p = newShell()
                s.windows += Win("window:demo-${ids.getAndIncrement()}", str("window_label")?.ifBlank { null } ?: "Shell", mutableListOf(p))
                viewChanged(s)
                buildJsonObject { put("block_ids", buildJsonArray { add(JsonPrimitive(p.id)) }) }
            }
            "session.create" -> {
                val p = newShell()
                val s = Sess("session:demo-${ids.getAndIncrement()}", str("label")?.ifBlank { null } ?: "New session",
                    mutableListOf(Win("window:demo-${ids.getAndIncrement()}", "Shell", mutableListOf(p))))
                sessions += s
                buildJsonObject {
                    put("session_id", s.id)
                    put("initial_windows", buildJsonArray { add(buildJsonObject { put("block_ids", buildJsonArray { add(JsonPrimitive(p.id)) }) }) })
                }
            }
            "session.destroy" -> { sessions.removeAll { it.id == str("session_id") }; JsonObject(emptyMap()) }
            "data.connect" -> buildJsonObject {
                val bid = str("block_id") ?: throw IllegalStateException("No block")
                put("ticket", Base64.encodeToString(bid.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            }
            "server.version" -> buildJsonObject { put("rex_version", "Demo"); put("platform", "macos_arm64"); put("api_revision", RexControl.API_REVISION) }
            "server.status" -> buildJsonObject { put("session_count", sessions.size) }
            "hosts.list" -> buildJsonObject { put("hosts", JsonArray(emptyList())) }
            else -> throw IllegalStateException("Not available in the demo")
        }
    }

    private fun newShell() = Pane("block:demo-${ids.getAndIncrement()}", "zsh", Kind.SHELL, "/Users/demo", "~", listOf("demo@mac ~ % "))

    // ---- terminals -------------------------------------------------------------------------

    private fun blockMethod(m: String, blockId: String?, args: JsonObject): JsonElement? {
        val p = pane(blockId) ?: throw IllegalStateException("No such pane")
        return when (m) {
            "title" -> buildJsonObject { put("title", p.title) }
            "program_status" -> buildJsonObject { put("records", buildJsonArray { if (p.state != null) add(record(p)) }) }
            "pwd" -> buildJsonObject { put("pwd", "file://demo-mac${p.cwd}") }
            "process" -> buildJsonObject {
                put("foreground", buildJsonObject {
                    val (name, path) = when (p.kind) {
                        Kind.CLAUDE -> "claude" to "/Users/demo/.local/bin/claude"
                        Kind.CODEX -> "codex" to "/opt/homebrew/bin/codex"
                        else -> "zsh" to "/bin/zsh"
                    }
                    put("name", name); put("path", path); put("cwd", p.cwd)
                })
            }
            "size" -> buildJsonObject { put("cols", p.cols); put("rows", p.rows) }
            "resize" -> {
                p.cols = args["cols"]?.jsonPrimitive?.intOrNull ?: p.cols
                p.rows = args["rows"]?.jsonPrimitive?.intOrNull ?: p.rows
                streams[p.id]?.forEach { sendResize(it, p) }
                redraw(p)
                JsonObject(emptyMap())
            }
            "format" -> buildJsonObject {
                val vt = args["format"]?.jsonPrimitive?.contentOrNull == "vt"
                put("content", if (vt) screenVt(p) else p.lines.joinToString("\n"))
            }
            "write" -> { input(p, args["data"]?.jsonPrimitive?.contentOrNull.orEmpty()); JsonObject(emptyMap()) }
            "clear", "reset" -> { if (p.kind == Kind.SHELL) { p.lines = listOf(prompt(p)); redraw(p) }; JsonObject(emptyMap()) }
            "list_dir" -> buildJsonObject {
                put("path", args["path"]?.jsonPrimitive?.contentOrNull ?: p.cwd)
                put("entries", buildJsonArray {
                    listOf("shop-api", "shop-web", "shop-docs").forEach { n -> add(buildJsonObject { put("name", n) }) }
                })
            }
            else -> throw IllegalStateException("Not available in the demo")
        }
    }

    private fun screenVt(p: Pane) = "\u001b[2J\u001b[H" + p.lines.joinToString("\r\n")

    private fun redraw(p: Pane) {
        val bytes = screenVt(p).toByteArray()
        streams[p.id]?.forEach { it.bytes(bytes) }
    }

    private fun output(p: Pane, s: String) { streams[p.id]?.forEach { it.bytes(s.toByteArray()) } }

    // ---- data protocol ---------------------------------------------------------------------

    private val nonce = 7L
    private fun apc(json: String) = "\u001b_rex1;$nonce;$json\u001b\\".toByteArray()
    private fun sendResize(ws: FakeSocket, p: Pane) =
        ws.bytes(apc(buildJsonObject { put("type", "resize"); put("cols", p.cols); put("rows", p.rows) }.toString()))

    private fun onBytes(ws: FakeSocket, data: ByteArray) {
        if (data.firstOrNull() != 0x01.toByte()) { ws.bytes(byteArrayOf(0x82.toByte(), 1)); return }
        // The app sends the ticket's bytes (it decodes data.connect's base64), which here are the block ID.
        val bid = String(data, 1, data.size - 1)
        val p = pane(bid) ?: run { ws.bytes(byteArrayOf(0x82.toByte(), 2)); return }
        ws.block = bid
        streams.getOrPut(bid) { ArrayList() } += ws
        val welcome = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        welcome.put(0x81.toByte()); repeat(16) { welcome.put(it.toByte()) }
        welcome.position(24); welcome.putLong(nonce)
        ws.bytes(welcome.array())
        // No snapshot: the size marks its end, and the app then asks for the screen (format vt).
        sendResize(ws, p)
    }

    // ---- what typing does ------------------------------------------------------------------

    private fun prompt(p: Pane) = "demo@mac ${p.cwd.substringAfterLast('/').let { if (p.cwd == "/Users/demo") "~" else it }} % "

    private fun input(p: Pane, data: String) {
        when (p.kind) {
            Kind.HELPER -> if (!p.started && data.contains('\r')) startHelper(p)
            Kind.SHELL -> shellInput(p, data)
            Kind.CLAUDE, Kind.CODEX -> agentInput(p, data)
        }
    }

    private fun shellInput(p: Pane, data: String) {
        for (c in data) when (c) {
            '\r', '\n' -> {
                val cmd = p.typed.toString().trim(); p.typed.clear()
                val reply = when {
                    cmd.isEmpty() -> emptyList()
                    cmd == "ls" -> listOf("shop-api   shop-docs   shop-web")
                    cmd == "pwd" -> listOf(p.cwd)
                    cmd == "whoami" -> listOf("demo")
                    cmd == "clear" -> { p.lines = emptyList(); emptyList() }
                    cmd.startsWith("echo ") -> listOf(cmd.removePrefix("echo "))
                    else -> listOf("This is the demo, so nothing runs here. Connect your Mac to use a real shell.")
                }
                if (p.state == "error") { p.state = null; p.msg = null; statusChanged(p) }
                val last = p.lines.lastOrNull().orEmpty()
                p.lines = p.lines.dropLast(1) + (last + cmd) + reply + prompt(p)
                if (cmd == "clear") redraw(p) else output(p, "\r\n" + reply.joinToString("") { "$it\r\n" } + prompt(p))
            }
            '\u007f', '\b' -> if (p.typed.isNotEmpty()) { p.typed.deleteCharAt(p.typed.length - 1); output(p, "\b \b") }
            '\u0003' -> { p.typed.clear(); p.lines = p.lines + prompt(p); output(p, "^C\r\n" + prompt(p)) }
            else -> if (c >= ' ') { p.typed.append(c); output(p, c.toString()) }
        }
    }

    private fun agentInput(p: Pane, data: String) {
        if (p.phase == Phase.ASKING) {
            when {
                data.startsWith("1") || data.startsWith("2") || data == "\r" -> approve(p)
                data.startsWith("3") || data.startsWith("\u001b") -> decline(p)
            }
            return
        }
        if (data.startsWith("\u001b") && data.length == 1) {
            if (p.phase == Phase.WORKING) {
                p.phase = Phase.IDLE; p.state = "idle"; p.msg = "Interrupted"; p.progress = null; statusChanged(p)
                p.lines = p.lines.filterNot { it.contains("esc to interrupt") } + "  ⎿  Interrupted · What should it do instead?"
                redraw(p)
            }
            return
        }
        for (c in data) when (c) {
            '\r', '\n' -> {
                val text = p.typed.toString().trim(); p.typed.clear()
                if (text.isEmpty()) continue
                val reply = "This is the demo, so nothing runs here. Connect your Mac to work with your real agents."
                val mark = if (p.kind == Kind.CODEX) "•" else "⏺"
                val ask = if (p.kind == Kind.CODEX) "›" else "❯"
                p.lines = p.lines.filterNot { it.startsWith("─") || it == "❯ " || it.startsWith("  ? for") || it.startsWith("› Ask") || it.contains("GPT-6") } +
                    listOf("$ask $text", "", "$mark $reply", "") + if (p.kind == Kind.CODEX) listOf("› Ask Codex to do anything", "  GPT-6-Luna medium · ~/${p.cwd.substringAfterLast('/')}") else claudeIdle()
                val n = ids.getAndIncrement()
                chat(p, user("${p.id}-u$n", text)); chat(p, text("${p.id}-a$n", reply))
                redraw(p)
            }
            '\u007f', '\b' -> if (p.typed.isNotEmpty()) p.typed.deleteCharAt(p.typed.length - 1)
            else -> if (c >= ' ') p.typed.append(c)
        }
    }

    private fun approve(p: Pane) {
        p.phase = Phase.RUNNING
        p.state = "working"; p.kind7501 = null; p.msg = "Running the auth tests"; statusChanged(p)
        p.lines = p.lines.takeWhile { !it.startsWith("────") } + listOf(
            "⏺ Bash(npm test -- auth)", "  ⎿  Running…", "", "✻ Running tests… (esc to interrupt)") + claudeIdle()
        chat(p, tool("api-run", "Bash", """{"command":"npm test -- auth"}"""))
        redraw(p)
        scope.launch {
            delay(4_000)
            if (p.phase != Phase.RUNNING) return@launch
            p.phase = Phase.IDLE
            p.state = "done"; p.msg = "All 42 auth tests pass"; statusChanged(p)
            p.lines = p.lines.filterNot { it.contains("esc to interrupt") || it.startsWith("─") || it == "❯ " || it.startsWith("  ? for") }
                .map { if (it == "  ⎿  Running…") "  ⎿  42 passed (3.1s)" else it } +
                listOf("⏺ All 42 auth tests pass, including the new", "  login timeout test.", "") + claudeIdle()
            chat(p, result("api-run", "Test Suites: 6 passed\nTests: 42 passed (3.1s)"))
            chat(p, text("api-done", "All 42 auth tests pass, including the new login timeout test."))
            redraw(p)
        }
    }

    private fun decline(p: Pane) {
        p.phase = Phase.IDLE
        p.state = "idle"; p.kind7501 = null; p.msg = "Waiting for you"; statusChanged(p)
        p.lines = p.lines.takeWhile { !it.startsWith("────") } + listOf("  ⎿  Not run · What should Claude do instead?", "") + claudeIdle()
        redraw(p)
    }

    private fun finishCodex(p: Pane) {
        p.phase = Phase.IDLE
        p.state = "done"; p.msg = "Checkout form split into 3 components"; p.progress = null; statusChanged(p)
        p.lines = p.lines.filterNot { it.contains("esc to interrupt") || it.startsWith("› Ask") || it.contains("GPT-6") } + listOf(
            "• Split CheckoutForm into AddressSection,", "  PaymentSection and OrderSummary. Lint and", "  tests pass.", "",
            "› Ask Codex to do anything", "  GPT-6-Luna medium · ~/shop-web")
        chat(p, text("web-done", "Split **CheckoutForm** into `AddressSection`, `PaymentSection` and `OrderSummary`. Lint and tests pass."))
        redraw(p)
    }

    // ---- chat view helpers -----------------------------------------------------------------

    private fun newHelper(payload: JsonObject): JsonElement {
        val opts = payload["options"] as? JsonObject
        val cwd = opts?.get("cwd")?.jsonPrimitive?.contentOrNull
        val sid = payload["session_id"]?.jsonPrimitive?.contentOrNull
        // The pane this helper reads for: the agent in that session and folder.
        val target = sessions.firstOrNull { it.id == sid }?.windows?.flatMap { it.panes }
            ?.firstOrNull { it.cwd == cwd && it.kind != Kind.SHELL }
        val h = Pane("block:demo-helper-${ids.getAndIncrement()}", payload["label"]?.jsonPrimitive?.contentOrNull ?: "helper",
            Kind.HELPER, cwd ?: "/Users/demo", "", emptyList())
        h.follows = target
        helpers += h
        return buildJsonObject { put("block_id", h.id) }
    }

    private fun startHelper(h: Pane) {
        h.started = true
        val p = h.follows
        if (p == null) { output(h, "{\"t\":\"rhubarb\",\"none\":1}\r\n"); return }
        output(h, "{\"t\":\"rhubarb\",\"ok\":1}\r\n" + p.chat.joinToString("") { "$it\r\n" })
    }

    private fun chat(p: Pane, line: String) {
        p.chat += line
        helpers.filter { it.follows == p && it.started }.forEach { output(it, "$line\r\n") }
    }
}
