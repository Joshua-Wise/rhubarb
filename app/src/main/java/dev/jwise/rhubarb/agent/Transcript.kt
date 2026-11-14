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

    private val skipPrefixes = listOf("<local-command-stdout>", "<local-command-caveat>", "<system-reminder>", "<local-command-stderr>",
        "Caveat:", "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<task-notification>")

    fun feed(o: JsonObject) {
        if (o["side"] != null || o["meta"] != null) return
        val type = o["t"]?.jsonPrimitive?.contentOrNull
        val key = (o["u"]?.jsonPrimitive?.contentOrNull ?: "x") + "#" + (n++)
        val str = o["s"]?.jsonPrimitive?.contentOrNull
        val parts = o["p"] as? JsonArray
        when (type) {
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
    fun items(): List<ChatItem> {
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
class TranscriptFollower(private val h: RexHub, private val sessionId: String, private val scope: CoroutineScope, installId: String) {
    /** This install's helpers carry this label, so leftovers (the app was killed) can be found and closed. */
    private val label = "rhubarb-chat:$installId"
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _status = MutableStateFlow<String?>("Loading the conversation…")
    val status: StateFlow<String?> = _status

    private var job: Job? = null
    private var blockId: String? = null
    private var stream: TerminalStream? = null
    private val model = ChatModel()

    fun start(cwd: String) {
        if (job != null) return
        job = scope.launch {
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
                            add(kotlinx.serialization.json.JsonPrimitive(SH)); add(kotlinx.serialization.json.JsonPrimitive("sh"))
                            add(kotlinx.serialization.json.JsonPrimitive(PERL))
                        })
                    })
                })!!.jsonObject
                val bid = r["block_id"]!!.jsonPrimitive.content
                blockId = bid
                val s = TerminalStream(h.control, sessionId, bid, scope, null)
                stream = s
                val buf = StringBuilder()
                var started = false
                var dirty = false
                launch {
                    while (true) { delay(120); if (dirty) { dirty = false; _items.value = model.items() } }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    s.events.collect { ev ->
                        when (ev) {
                            is TermEvent.Resize -> if (!started) { started = true; h.write(sessionId, bid, "\r") }
                            is TermEvent.Output -> {
                                buf.append(String(ev.bytes, Charsets.UTF_8))
                                while (true) {
                                    val nl = buf.indexOf("\n"); if (nl < 0) break
                                    val line = buf.substring(0, nl).trim(); buf.delete(0, nl + 1)
                                    // The first line can carry the terminal's own redraw codes in front of it.
                                    val brace = line.indexOf('{'); if (brace < 0) continue
                                    val json = line.substring(brace)
                                    val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: continue
                                    if (o["t"]?.jsonPrimitive?.contentOrNull == "rhubarb") {
                                        _status.value = if (o["none"] != null) "No Claude Code conversation found for this folder." else null
                                    } else { model.feed(o); dirty = true; _status.value = null }
                                }
                            }
                            TermEvent.Closed -> if (_items.value.isEmpty()) _status.value = "The conversation reader stopped."
                            else -> Unit
                        }
                    }
                }
                s.start()
            } catch (e: Exception) {
                _status.value = "Couldn't read the conversation: ${e.message}"
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        stream?.stop(); stream = null
        val bid = blockId ?: return
        blockId = null
        scope.launch {
            runCatching { h.control.call("block.close", buildJsonObject { put("session_id", sessionId); put("block_id", bid) }) }
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
