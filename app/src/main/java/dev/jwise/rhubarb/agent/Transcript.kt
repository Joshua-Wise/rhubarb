package dev.jwise.rhubarb.agent

import dev.jwise.rhubarb.rex.RexHub
import dev.jwise.rhubarb.rex.TermEvent
import dev.jwise.rhubarb.rex.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One thing in a Claude Code conversation, as the chat view shows it. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val images: Int = 0) : ChatItem
    data class Assistant(override val key: String, val text: String) : ChatItem
    /** A slash command the user ran, like "/model". */
    data class Command(override val key: String, val text: String) : ChatItem
    data class Note(override val key: String, val text: String) : ChatItem
    /** Consecutive tool calls, shown as one collapsible row ("Ran 2 commands, read 1 file"). */
    data class Tools(override val key: String, val calls: List<ToolCall>) : ChatItem
}

data class ToolCall(val id: String, val name: String, val input: JsonObject?, val result: String? = null, val error: Boolean = false) {
    private fun s(k: String) = input?.get(k)?.jsonPrimitive?.contentOrNull
    private fun base(p: String?) = p?.substringAfterLast('/')

    /** One line saying what the call did, like "npm test" or "Read App.kt". */
    val summary: String
        get() = when (name) {
            "Bash" -> s("command") ?: "Ran a command"
            "Read" -> "Read ${base(s("file_path")) ?: "a file"}"
            "Edit", "MultiEdit" -> "Edited ${base(s("file_path")) ?: "a file"}"
            "Write" -> "Wrote ${base(s("file_path")) ?: "a file"}"
            "Grep" -> "Searched for ${s("pattern") ?: "text"}"
            "Glob" -> "Found files ${s("pattern") ?: ""}".trim()
            "WebFetch" -> "Fetched ${s("url") ?: "a page"}"
            "WebSearch" -> "Searched the web for ${s("query") ?: ""}".trim()
            "Agent", "Task" -> "Agent: ${s("description") ?: "a subtask"}"
            "TodoWrite", "TaskCreate", "TaskUpdate" -> "Updated the task list"
            else -> name.removePrefix("mcp__").replace("__", " · ")
        }

    /** The verb this call counts toward in a group's title. */
    val kind: String
        get() = when (name) {
            "Bash" -> "command"
            "Read" -> "file read"
            "Edit", "MultiEdit", "Write" -> "edit"
            "Grep", "Glob", "WebSearch" -> "search"
            "WebFetch" -> "fetch"
            else -> "tool"
        }
}

/** "Ran 2 commands, read 1 file" for a group of tool calls. */
fun toolsTitle(calls: List<ToolCall>): String {
    val counts = calls.groupingBy { it.kind }.eachCount()
    fun n(k: String, one: String, many: String) = counts[k]?.let { if (it == 1) "1 $one" else "$it $many" }
    val parts = listOfNotNull(
        n("command", "command", "commands")?.let { "ran $it" },
        n("file read", "file", "files")?.let { "read $it" },
        n("edit", "file", "files")?.let { "edited $it" },
        n("search", "search", "searches")?.let { "made $it" },
        n("fetch", "page", "pages")?.let { "fetched $it" },
        n("tool", "tool", "tools")?.let { "used $it" },
    )
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Used tools" }
}

/**
 * Builds the conversation from slimmed transcript lines (see [TranscriptFollower]). Lines arrive
 * in order; tool results attach to their calls by ID.
 */
class ChatModel {
    private sealed interface Entry {
        data class Item(val item: ChatItem) : Entry
        data class Call(val call: ToolCall) : Entry
    }
    private val entries = ArrayList<Entry>()
    private val results = HashMap<String, Pair<String, Boolean>>()
    private var n = 0
    private val seen = HashSet<String>()

    private val skipPrefixes = listOf("<local-command-stdout>", "<local-command-caveat>", "<system-reminder>", "<local-command-stderr>",
        "Caveat:", "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<task-notification>")

    // Lines arrive on the stream's thread while the screen reads items() on another.
    @Synchronized fun feed(o: JsonObject) {
        if (o["side"] != null || o["meta"] != null) return
        val type = o["t"]?.jsonPrimitive?.contentOrNull
        // Lines carry their own IDs: one already shown (the backlog is sent again when the reader
        // restarts) changes nothing, and keys stay the same so the list keeps its place.
        val u = o["u"]?.jsonPrimitive?.contentOrNull
        if (u != null && !seen.add(u)) return
        val key = u ?: ("x#" + (n++))
        val str = o["s"]?.jsonPrimitive?.contentOrNull
        val parts = o["p"] as? JsonArray
        when (type) {
            // Claude Code's summary of the conversation so far, written when it compacts.
            "note" -> str?.let { entries += Entry.Item(ChatItem.Note(key, it)) }
            "user" -> {
                if (str != null) userText(key, str, 0)
                parts?.forEachIndexed { i, p ->
                    val po = p.jsonObject
                    when (po["t"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> userText("$key.$i", po["x"]?.jsonPrimitive?.contentOrNull.orEmpty(), 0)
                        "image" -> entries += Entry.Item(ChatItem.User("$key.$i", "", 1))
                        "result" -> {
                            val id = po["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                            results[id] = (po["x"]?.jsonPrimitive?.contentOrNull.orEmpty()) to (po["e"]?.jsonPrimitive?.contentOrNull == "1")
                        }
                    }
                }
            }
            "assistant" -> parts?.forEachIndexed { i, p ->
                val po = p.jsonObject
                when (po["t"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> po["x"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        entries += Entry.Item(ChatItem.Assistant("$key.$i", it))
                    }
                    "tool" -> {
                        val input = po["i"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                        entries += Entry.Call(ToolCall(po["id"]?.jsonPrimitive?.contentOrNull ?: "$key.$i",
                            po["n"]?.jsonPrimitive?.contentOrNull ?: "tool", input))
                    }
                }
            }
        }
    }

    private fun userText(key: String, raw: String, images: Int) {
        val t = raw.trim()
        if (t.isEmpty() || skipPrefixes.any { t.startsWith(it) }) return
        Regex("""<command-name>(.*?)</command-name>""").find(t)?.let { m ->
            val args = Regex("""<command-args>(.*?)</command-args>""", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1)?.trim()
            entries += Entry.Item(ChatItem.Command(key, listOfNotNull(m.groupValues[1].trim(), args?.ifBlank { null }).joinToString(" ")))
            return
        }
        if (t.startsWith("[Request interrupted")) { entries += Entry.Item(ChatItem.Note(key, "Interrupted")); return }
        entries += Entry.Item(ChatItem.User(key, t, images))
    }

    /** The conversation so far, with consecutive tool calls grouped. */
    @Synchronized fun items(): List<ChatItem> {
        val out = ArrayList<ChatItem>()
        var group = ArrayList<ToolCall>()
        fun flush() { if (group.isNotEmpty()) { out += ChatItem.Tools("tools:" + group.first().id, group); group = ArrayList() } }
        for (e in entries) when (e) {
            is Entry.Call -> group += results[e.call.id]?.let { (x, err) -> e.call.copy(result = x, error = err) } ?: e.call
            is Entry.Item -> { flush(); out += e.item }
        }
        flush()
        return out
    }
}

/**
 * Follows a Claude Code pane's transcript through a hidden (detached) Rex terminal on the pane's
 * machine. The helper finds the newest transcript for the pane's folder, sends the last part of it
 * and then new lines as Claude writes them, trimmed on the Mac by a small Perl filter to messages,
 * tool calls and capped tool output. It waits for a go-ahead before sending so nothing is printed
 * before the stream is connected, ends itself after two hours, and is closed when the pane closes.
 */
class TranscriptFollower(
    private var h: RexHub, private val sessionId: String, private val scope: CoroutineScope, installId: String,
    private val source: TranscriptSource = TranscriptSources.claude,
    /** The pane this reads for: its helper's label names it, so other panes' helpers are left alone. */
    paneId: String = "",
) {
    /** Where the chat was scrolled to (the item at the top of the view and how far into it), kept
     *  while you're in the terminal or away from the app. Null: at the newest message. */
    var anchor: Pair<String, Int>? = null
    /** Terminal or chat, as last chosen for this pane (null: the setting decides). */
    var choice: Boolean? = null

    /** This install's helpers carry this label, so leftovers (the app was killed) can be found and closed. */
    private val label = "rhubarb-chat:$installId" + (if (paneId.isNotEmpty()) ":$paneId" else "")
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _status = MutableStateFlow<String?>("Loading the conversation…")
    val status: StateFlow<String?> = _status

    private var job: Job? = null
    private var blockId: String? = null
    private var stream: TerminalStream? = null
    private val model = ChatModel()

    /** Starts (or restarts, on a new connection) reading. Items already shown stay. */
    fun start(cwd: String, hub: RexHub = h) {
        paused = false
        if (job?.isActive == true && hub === h) return
        if (job != null) stop()
        h = hub
        lastCwd = cwd
        val h = hub
        // Nothing here may take the app down: a failure shows in the chat instead.
        val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.w("Transcript", "chat reader failed", e)
            _status.value = "The conversation reader stopped: ${e.message ?: e::class.simpleName}"
        }
        job = scope.launch(guard + kotlinx.coroutines.SupervisorJob()) {
            try {
                // Close this phone's helpers left over from before (Android ended the app early).
                runCatching {
                    (h.control.call("session.list_blocks", buildJsonObject { put("session_id", sessionId) })?.jsonObject?.get("blocks") as? JsonArray)
                        ?.map { it.jsonObject }
                        ?.filter { it["placement"]?.jsonPrimitive?.contentOrNull == "detached" && it["label"]?.jsonPrimitive?.contentOrNull == label }
                        ?.forEach { b -> h.control.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", b["block_id"]!!.jsonPrimitive.content) }) }
                }
                val r = h.control.call("session.new_block", buildJsonObject {
                    put("session_id", sessionId)
                    put("flavor", "com.superlogical.terminal.shell")
                    put("label", label)
                    put("options", buildJsonObject {
                        put("cwd", cwd)
                        put("command", buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("/usr/bin/perl"))
                            add(kotlinx.serialization.json.JsonPrimitive("-e")); add(kotlinx.serialization.json.JsonPrimitive("alarm 7200; exec @ARGV"))
                            add(kotlinx.serialization.json.JsonPrimitive("/bin/sh")); add(kotlinx.serialization.json.JsonPrimitive("-c"))
                            add(kotlinx.serialization.json.JsonPrimitive(source.sh)); add(kotlinx.serialization.json.JsonPrimitive("sh"))
                            add(kotlinx.serialization.json.JsonPrimitive(source.perl))
                        })
                    })
                })!!.jsonObject
                val bid = r["block_id"]!!.jsonPrimitive.content
                blockId = bid
                val s = TerminalStream(h.control, sessionId, bid, scope, null)
                stream = s
                var pending = ByteArray(0)
                var started = false
                var dirty = false
                // The first time, show the conversation once the backlog has arrived (output quiet for
                // a moment), not piece by piece; after that, update as lines come in.
                var shown = _items.value.isNotEmpty()
                var lastOut = System.currentTimeMillis()
                val began = lastOut
                launch {
                    while (true) {
                        delay(120)
                        val now = System.currentTimeMillis()
                        if (!shown && (now - lastOut > 400 || now - began > 15_000)) shown = true
                        if (dirty && shown) { dirty = false; _items.value = model.items(); _status.value = null }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    s.events.collect { ev ->
                        when (ev) {
                            is TermEvent.Resize -> if (!started) { started = true; h.write(sessionId, bid, "\r") }
                            is TermEvent.Output -> {
                                lastOut = System.currentTimeMillis()
                                // Decode only whole lines (a character can be split across chunks);
                                // keep the unfinished last line for the next one.
                                val bytes = pending + ev.bytes
                                val last = bytes.lastIndexOf('\n'.code.toByte())
                                if (last < 0) { pending = bytes; return@collect }
                                val done = String(bytes, 0, last, Charsets.UTF_8)
                                pending = bytes.copyOfRange(last + 1, bytes.size)
                                for (raw in done.split('\n')) {
                                    val line = raw.trim()
                                    // The first line can carry the terminal's own redraw codes in front of it.
                                    val brace = line.indexOf('{'); if (brace < 0) continue
                                    val json = line.substring(brace)
                                    val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: continue
                                    if (o["t"]?.jsonPrimitive?.contentOrNull == "rhubarb") {
                                        _status.value = if (o["none"] != null) "No ${source.program} conversation found for this folder." else if (_items.value.isEmpty()) "Loading the conversation…" else null
                                    } else { runCatching { model.feed(o) }; dirty = true }
                                }
                            }
                            TermEvent.Closed -> {
                                if (blockId == bid) blockId = null
                                restartLater(bid)
                            }
                            else -> Unit
                        }
                    }
                }
                s.start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Couldn't read the conversation: ${e.message}"
            }
        }
    }

    fun stop() {
        stopped()
        val bid = blockId ?: return
        blockId = null
        val hub = h
        scope.launch {
            runCatching { hub.control.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", bid) }) }
        }
    }

    private fun stopped() {
        job?.cancel(); job = null
        restart?.cancel(); restart = null
        _status.value = if (_items.value.isEmpty()) "Loading the conversation…" else null
        stream?.stop(); stream = null
    }

    /** Closes the helper and waits for the Mac to confirm, while [hub] is still connected (the app is
     *  going to the background and is about to drop the connection). */
    suspend fun closeNow(hub: RexHub) {
        if (hub !== h || job == null) return
        paused = true
        stopped()
        val bid = blockId ?: return
        blockId = null
        runCatching { hub.control.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", bid) }) }
    }

    private var lastCwd: String? = null
    /** Closed by [closeNow]; [resume] starts it again if the app comes back on the same connection. */
    private var paused = false

    fun resume(hub: RexHub) {
        if (!paused || hub !== h) return
        paused = false
        lastCwd?.let { start(it, hub) }
    }
    private var restart: Job? = null
    private var lastRestart = 0L

    /** The helper ended on its own: start a new one (at most once a minute); lines already shown stay. */
    private fun restartLater(ended: String) {
        val cwd = lastCwd ?: return
        // No conversation for this folder: nothing to follow, and the message stays.
        if (_status.value?.startsWith("No ") == true) return
        val now = System.currentTimeMillis()
        if (now - lastRestart < 60_000) {
            _status.value = if (_items.value.isEmpty()) "The conversation reader stopped." else null
            return
        }
        lastRestart = now
        val hub = h
        restart = scope.launch {
            delay(1500)
            if (h !== hub || blockId != null && blockId != ended) return@launch
            job?.cancel(); job = null
            stream?.stop(); stream = null
            start(cwd, hub)
        }
    }

    companion object {
        /** Find the folder's newest transcript (Claude Code names the folder after the path), wait for the go-ahead, follow it. */
        internal const val SH = """d="${'$'}HOME/.claude/projects/${'$'}(pwd | sed 's/[^A-Za-z0-9]/-/g')"
f=${'$'}(ls -t "${'$'}d"/*.jsonl 2>/dev/null | grep -v '/agent-' | head -n 1)
stty -echo 2>/dev/null; read go
if [ -z "${'$'}f" ]; then echo '{"t":"rhubarb","none":1}'; exit 0; fi
echo '{"t":"rhubarb","ok":1}'
tail -n 6000 -F "${'$'}f" 2>/dev/null | perl -MJSON::PP -e "${'$'}1""""

        /** Keep only messages, tool calls and capped tool output; one compact JSON object per line. */
        internal const val PERL = """
${'$'}| = 1; my ${'$'}j = JSON::PP->new->utf8; my ${'$'}ji = JSON::PP->new;
sub cut { my (${'$'}s, ${'$'}n) = @_; return '' unless defined ${'$'}s; ${'$'}s =~ s/\s+${'$'}//; length(${'$'}s) > ${'$'}n ? substr(${'$'}s, 0, ${'$'}n) . "\x{2026}" : ${'$'}s }
while (my ${'$'}l = <STDIN>) {
  next unless ${'$'}l =~ /"type":"(user|assistant)"/;
  my ${'$'}o = eval { ${'$'}j->decode(${'$'}l) } or next;
  my ${'$'}m = ${'$'}o->{message} || {}; my ${'$'}c = ${'$'}m->{content};
  my %out = (t => ${'$'}o->{type}, u => ${'$'}o->{uuid});
  ${'$'}out{side} = 1 if ${'$'}o->{isSidechain}; ${'$'}out{meta} = 1 if ${'$'}o->{isMeta};
  if (${'$'}o->{isCompactSummary}) { print ${'$'}j->encode({ t => 'note', u => ${'$'}o->{uuid}, s => 'Conversation compacted' }), "\n"; next }
  if (!ref ${'$'}c) { ${'$'}out{s} = cut(${'$'}c, 8000) }
  else {
    my @p;
    for my ${'$'}p (@${'$'}c) {
      my ${'$'}t = ${'$'}p->{type} // '';
      if (${'$'}t eq 'text') { push @p, { t => 'text', x => cut(${'$'}p->{text}, 8000) } }
      elsif (${'$'}t eq 'tool_use') { push @p, { t => 'tool', id => ${'$'}p->{id}, n => ${'$'}p->{name}, i => cut(${'$'}ji->encode(${'$'}p->{input} // {}), 1500) } }
      elsif (${'$'}t eq 'tool_result') {
        my ${'$'}x = ${'$'}p->{content};
        ${'$'}x = join("\n", map { (${'$'}_->{type} // '') eq 'text' ? ${'$'}_->{text} : '[' . (${'$'}_->{type} // '?') . ']' } @${'$'}x) if ref ${'$'}x eq 'ARRAY';
        push @p, { t => 'result', id => ${'$'}p->{tool_use_id}, x => cut(${'$'}x, 2000), e => (${'$'}p->{is_error} ? 1 : 0) };
      }
      elsif (${'$'}t eq 'image') { push @p, { t => 'image' } }
    }
    next unless @p;
    ${'$'}out{p} = \@p;
  }
  print ${'$'}j->encode(\%out), "\n";
}"""
    }
}

/**
 * Chat readers outlive the pane screen (which goes away when you leave the app), so coming back
 * finds the conversation, the scroll position and the Terminal/Chat choice as they were. The few
 * most recently opened panes are kept.
 */
object ChatFollowers {
    private val kept = object : LinkedHashMap<String, TranscriptFollower>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, TranscriptFollower>?): Boolean =
            (size > 6).also { if (it) e?.value?.stop() }
    }
    @Synchronized fun get(key: String, make: () -> TranscriptFollower): TranscriptFollower = kept.getOrPut(key, make)

    /** Close the helpers running on [hub] (the app is leaving the screen and will drop it). */
    /** The app came back before the connection was dropped: reopen the chats [closeAll] closed. */
    fun resumeAll(hub: RexHub) {
        val all = synchronized(this) { kept.values.toList() }
        for (f in all) f.resume(hub)
    }

    suspend fun closeAll(hub: RexHub) {
        val all = synchronized(this) { kept.values.toList() }
        for (f in all) f.closeNow(hub)
    }
}

/**
 * Where an agent keeps its sessions and how to read them: [sh] finds the session for the pane's
 * folder and follows it (after the go-ahead), piping it through [perl], which turns the agent's own
 * records into the shared compact format [ChatModel] reads:
 * {"t":"user","s":text} · {"t":"assistant","p":[{"t":"text","x":…}|{"t":"tool","id","n","i"}]} ·
 * {"t":"user","p":[{"t":"result","id","x","e"}]}.
 */
class TranscriptSource(val program: String, val sh: String, val perl: String)

object TranscriptSources {
    /** Claude Code: ~/.claude/projects/<folder>/<session>.jsonl, Anthropic message records. */
    val claude = TranscriptSource("Claude Code", TranscriptFollower.SH, TranscriptFollower.PERL)

    /**
     * Codex: ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl, whose first line names the session's
     * folder. Its item_completed events carry what a person sees: user and agent messages, commands
     * with their output, file changes, tool calls and web searches.
     */
    val codex = TranscriptSource("Codex", CODEX_SH, CODEX_PERL)

    private val all = listOf(claude, codex)
    fun forProgram(name: String): TranscriptSource? = all.firstOrNull { it.program == name }
}

internal const val CODEX_SH = """stty -echo 2>/dev/null; read go
# The newest recent session whose first line names this folder (compared as parsed JSON, so any
# folder name matches; file names are passed NUL-separated, so spaces are fine).
f=${'$'}(find "${'$'}HOME/.codex/sessions" -name 'rollout-*.jsonl' -mtime -30 -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -n 60 |
  perl -MJSON::PP -e 'my ${'$'}c = ${'$'}ENV{PWD}; while (my ${'$'}x = <STDIN>) { chomp ${'$'}x; open(my ${'$'}h, "<", ${'$'}x) or next; my ${'$'}l = <${'$'}h>; close ${'$'}h;
    my ${'$'}o = eval { decode_json(${'$'}l) } or next; if ((${'$'}o->{payload}{cwd} // "") eq ${'$'}c) { print ${'$'}x; last } }')
if [ -z "${'$'}f" ]; then echo '{"t":"rhubarb","none":1}'; exit 0; fi
echo '{"t":"rhubarb","ok":1}'
tail -n 20000 -F "${'$'}f" 2>/dev/null | perl -MJSON::PP -e "${'$'}1""""

internal const val CODEX_PERL = """
${'$'}| = 1; my ${'$'}j = JSON::PP->new->utf8; my ${'$'}ji = JSON::PP->new;
sub cut { my (${'$'}s, ${'$'}n) = @_; return '' unless defined ${'$'}s; ${'$'}s =~ s/\s+${'$'}//; length(${'$'}s) > ${'$'}n ? substr(${'$'}s, 0, ${'$'}n) . "\x{2026}" : ${'$'}s }
sub texts { my (${'$'}c) = @_; return '' unless ref ${'$'}c eq 'ARRAY'; join("\n", map { ${'$'}_->{text} // '' } grep { ref ${'$'}_ eq 'HASH' } @${'$'}c) }
sub tool {
  my (${'$'}id, ${'$'}n, ${'$'}in, ${'$'}out, ${'$'}err) = @_;
  print ${'$'}j->encode({ t => 'assistant', u => ${'$'}id, p => [{ t => 'tool', id => ${'$'}id, n => ${'$'}n, i => cut(${'$'}ji->encode(${'$'}in), 1500) }] }), "\n";
  ${'$'}out = ${'$'}ji->encode(${'$'}out) if ref ${'$'}out;
  print ${'$'}j->encode({ t => 'user', u => "${'$'}id.r", p => [{ t => 'result', id => ${'$'}id, x => cut(${'$'}out // '', 2000), e => (${'$'}err ? 1 : 0) }] }), "\n";
}
while (my ${'$'}l = <STDIN>) {
  next unless ${'$'}l =~ /"item_completed"/;
  my ${'$'}o = eval { ${'$'}j->decode(${'$'}l) } or next;
  my ${'$'}it = (${'$'}o->{payload} || {})->{item} or next;
  my ${'$'}t = lc(${'$'}it->{type} // ''); my ${'$'}id = ${'$'}it->{id} // '';
  if (${'$'}t eq 'usermessage') { my ${'$'}x = texts(${'$'}it->{content}); print ${'$'}j->encode({ t => 'user', u => ${'$'}id, s => cut(${'$'}x, 8000) }), "\n" if length ${'$'}x }
  elsif (${'$'}t eq 'agentmessage') { my ${'$'}x = texts(${'$'}it->{content}); print ${'$'}j->encode({ t => 'assistant', u => ${'$'}id, p => [{ t => 'text', x => cut(${'$'}x, 8000) }] }), "\n" if length ${'$'}x }
  elsif (${'$'}t eq 'commandexecution') {
    my ${'$'}c = ${'$'}it->{command};
    ${'$'}c = ref ${'$'}c eq 'ARRAY' ? ((@${'$'}c >= 3 && ${'$'}c->[1] =~ /^-l?c${'$'}/) ? ${'$'}c->[2] : join(' ', @${'$'}c)) : (${'$'}c // '');
    tool(${'$'}id, 'Bash', { command => ${'$'}c }, ${'$'}it->{aggregated_output}, ((${'$'}it->{exit_code} // 0) != 0 || (${'$'}it->{status} // '') eq 'failed'));
  }
  elsif (${'$'}t eq 'filechange') {
    my @c = @{ ${'$'}it->{changes} || [] };
    my @lines = map { my ${'$'}k = ${'$'}_->{kind}; ${'$'}k = ${'$'}k->{type} if ref ${'$'}k eq 'HASH'; (${'$'}k // 'edit') . ' ' . (${'$'}_->{path} // '') } @c;
    tool(${'$'}id, @c > 1 ? 'MultiEdit' : 'Edit', { file_path => (@c ? (${'$'}c[0]{path} // '') : '') }, join("\n", @lines), (${'$'}it->{status} // '') eq 'failed');
  }
  elsif (${'$'}t eq 'mcptoolcall') {
    tool(${'$'}id, 'mcp__' . (${'$'}it->{server} // 'mcp') . '__' . (${'$'}it->{tool} // 'tool'), ref ${'$'}it->{arguments} eq 'HASH' ? ${'$'}it->{arguments} : {}, ${'$'}it->{result} // ${'$'}it->{error}, defined ${'$'}it->{error});
  }
  elsif (${'$'}t eq 'websearch') { tool(${'$'}id, 'WebSearch', { query => ${'$'}it->{query} // '' }, '', 0) }
}"""

