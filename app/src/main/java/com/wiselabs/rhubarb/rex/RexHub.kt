package com.wiselabs.rhubarb.rex

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BlockInfo(val blockId: String, val label: String, val creator: String)
data class WindowInfo(val windowId: String, val label: String, val focusedBlockId: String?, val blocks: List<BlockInfo>)
data class SessionInfo(
    val sessionId: String,
    val label: String,
    val revision: Long = 0,
    val activeWindowId: String? = null,
    val windows: List<WindowInfo> = emptyList(),
)

/** One OSC 7501 status record a program (e.g. Claude Code) reported. */
data class ProgramStatus(
    val id: String,
    val state: String,          // idle | working | done | blocked | error
    val kind: String?,          // permission | question | auth (when blocked)
    val app: String?,
    val title: String?,
    val msg: String?,
    val progress: Int?,
    val updatedAt: String?,
)

data class BlockLive(
    val title: String = "",
    val pwd: String? = null,
    /** The foreground process: its executable name and path. */
    val processName: String? = null,
    val processPath: String? = null,
    val statuses: Map<String, ProgramStatus> = emptyMap(),
) {
    /** The most attention-worthy record. */
    val top: ProgramStatus?
        get() = statuses.values.maxWithOrNull(compareBy<ProgramStatus>({ rank(it.state) }, { it.updatedAt ?: "" }))
    companion object {
        fun rank(s: String) = when (s) { "blocked" -> 5; "error" -> 4; "working" -> 3; "done" -> 2; else -> 1 }
    }
}

fun parseStatus(id: String, rec: JsonObject) = ProgramStatus(
    id = id,
    state = rec["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
    kind = rec["kind"]?.jsonPrimitive?.contentOrNull,
    app = rec["effective_app"]?.jsonPrimitive?.contentOrNull ?: rec["app"]?.jsonPrimitive?.contentOrNull,
    title = rec["title"]?.jsonPrimitive?.contentOrNull,
    msg = rec["msg"]?.jsonPrimitive?.contentOrNull,
    progress = rec["progress"]?.jsonPrimitive?.intOrNull,
    updatedAt = rec["updated_at"]?.jsonPrimitive?.contentOrNull,
)

fun parseView(v: JsonObject): SessionInfo {
    val windows = (v["windows"] as? JsonArray ?: JsonArray(emptyList())).map { w ->
        val wo = w.jsonObject
        val blocks = mutableListOf<BlockInfo>()
        (wo["layers"] as? JsonArray)?.forEach { layer ->
            (layer.jsonObject["blocks"] as? JsonArray)?.forEach { b ->
                val bo = b.jsonObject
                blocks += BlockInfo(
                    bo["block_id"]!!.jsonPrimitive.content,
                    bo["label"]?.jsonPrimitive?.contentOrNull ?: "",
                    bo["creator_name"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        }
        WindowInfo(
            wo["window_id"]!!.jsonPrimitive.content,
            wo["label"]?.jsonPrimitive?.contentOrNull ?: "",
            wo["focused_block_id"]?.jsonPrimitive?.contentOrNull,
            blocks,
        )
    }
    return SessionInfo(
        sessionId = v["session_id"]!!.jsonPrimitive.content,
        label = v["label"]?.jsonPrimitive?.contentOrNull ?: "",
        revision = v["revision"]?.jsonPrimitive?.longOrNull ?: 0,
        activeWindowId = v["active_window_id"]?.jsonPrimitive?.contentOrNull,
        windows = windows,
    )
}

const val TERMINAL = "com.superlogical.terminal"

/**
 * Keeps a live picture of a Rex server: every session's layout plus each terminal's
 * title and program status. Used by both the UI and the background watcher.
 *
 * [onBlockEvent] sees every block_event (bell, desktop_notification, program_status_changed…)
 * with the hub's state already updated.
 */
class RexHub(
    baseUrl: String,
    private val scope: CoroutineScope,
    clientName: String,
    clientLabel: () -> String? = { null },
    private val onBlockEvent: (sessionId: String, blockId: String, name: String, payload: JsonObject?, hub: RexHub) -> Unit = { _, _, _, _, _ -> },
) {
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _live = MutableStateFlow<Map<String, BlockLive>>(emptyMap())
    val live: StateFlow<Map<String, BlockLive>> = _live

    val control = RexControl(baseUrl, scope, clientName, clientLabel, onConnected = { c, st -> onConnected(c, st) })
    val state get() = control.state
    private var eventsJob: Job? = null

    fun start() {
        if (eventsJob == null) eventsJob = scope.launch { control.events.collect { handleEvent(it) } }
        control.start()
    }

    fun stop() { control.stop(); eventsJob?.cancel(); eventsJob = null }

    private suspend fun onConnected(c: RexControl, st: ConnState.Connected) {
        if (st.resumed) return // server kept our attachments; events lost while away are refreshed below anyway
        // event_scope=all also delivers block_event from every session (statuses, bells, notifications).
        c.callNow("session.attach_all", buildJsonObject { put("event_scope", "all") })
        val list = c.callNow("session.list")!!.jsonObject["sessions"]!!.jsonArray
        val sessions = mutableListOf<SessionInfo>()
        for (s in list) {
            val sid = s.jsonObject["session_id"]!!.jsonPrimitive.content
            // Full attach is needed for data connections (terminal output) later.
            val att = c.callNow("session.attach", buildJsonObject { put("session_id", sid) })!!.jsonObject
            sessions += parseView(att["view"]!!.jsonObject)
        }
        _sessions.value = sessions
        scope.launch { refreshAllLive() }
    }

    /** Re-read every session and terminal (pull to refresh). */
    suspend fun refreshAll() {
        try {
            val list = control.call("session.list")!!.jsonObject["sessions"]!!.jsonArray
            val sessions = mutableListOf<SessionInfo>()
            for (s in list) {
                val sid = s.jsonObject["session_id"]!!.jsonPrimitive.content
                val att = control.call("session.attach", buildJsonObject { put("session_id", sid) })!!.jsonObject
                sessions += parseView(att["view"]!!.jsonObject)
            }
            _sessions.value = sessions
            refreshAllLive()
        } catch (e: Exception) { Log.w("RexHub", "refreshAll: ${e.message}") }
    }

    /** Other Rex servers this server knows about (Rex's "hosts"), as label to https endpoint. */
    suspend fun hosts(): List<Pair<String, String>> = try {
        (control.call("hosts.list")?.jsonObject?.get("hosts") as? JsonArray)?.mapNotNull { h ->
            val o = h.jsonObject
            val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val ep = (o["endpoints"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.firstOrNull { it.startsWith("https://") || it.startsWith("wss://") } ?: return@mapNotNull null
            label to ep
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    /** Rename this client live, without reconnecting. */
    suspend fun setLabel(label: String) {
        try { control.call("client.set_label", buildJsonObject { put("label", label) }) } catch (_: Exception) {}
    }

    suspend fun refreshAllLive() {
        for (s in _sessions.value) for (w in s.windows) for (b in w.blocks) if (b.creator == TERMINAL) refreshBlock(s.sessionId, b.blockId)
    }

    suspend fun refreshBlock(sessionId: String, blockId: String) {
        try {
            val title = control.blockCall(sessionId, blockId, "$TERMINAL.title")?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
            val recs = control.blockCall(sessionId, blockId, "$TERMINAL.program_status")?.jsonObject?.get("records") as? JsonArray
            val statuses = recs?.associate { r ->
                val ro = r.jsonObject; val id = ro["id"]?.jsonPrimitive?.contentOrNull ?: ""
                id to parseStatus(id, ro)
            } ?: emptyMap()
            val pwd = try {
                control.blockCall(sessionId, blockId, "$TERMINAL.pwd")?.jsonObject?.get("pwd")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) { null }
            val fg = try {
                control.blockCall(sessionId, blockId, "$TERMINAL.process")?.jsonObject?.get("foreground") as? JsonObject
            } catch (e: Exception) { null }
            _live.update {
                it + (blockId to BlockLive(
                    title, pwd,
                    processName = fg?.get("name")?.jsonPrimitive?.contentOrNull,
                    processPath = fg?.get("path")?.jsonPrimitive?.contentOrNull,
                    statuses = statuses,
                ))
            }
        } catch (e: Exception) {
            Log.w("RexHub", "refresh $blockId: ${e.message}")
        }
    }

    private suspend fun refreshView(sessionId: String, minRevision: Long) {
        val cur = _sessions.value.firstOrNull { it.sessionId == sessionId }
        if (cur != null && cur.revision >= minRevision) return
        try {
            val v = control.call("session.view", buildJsonObject { put("session_id", sessionId) })!!.jsonObject
            val view = parseView(v["view"]?.jsonObject ?: v)
            _sessions.update { list -> list.map { if (it.sessionId == sessionId) view else it } }
            for (w in view.windows) for (b in w.blocks) if (b.creator == TERMINAL && _live.value[b.blockId] == null) refreshBlock(sessionId, b.blockId)
        } catch (e: Exception) { Log.w("RexHub", "view: ${e.message}") }
    }

    private fun updateLive(blockId: String, f: (BlockLive) -> BlockLive) =
        _live.update { it + (blockId to f(it[blockId] ?: BlockLive())) }

    private fun handleEvent(ev: JsonObject) {
        val type = ev["type"]?.jsonPrimitive?.contentOrNull
        val payload = ev["payload"] as? JsonObject
        when (type) {
            "session_created" -> {
                val sid = payload?.get("session")?.jsonObject?.get("session_id")?.jsonPrimitive?.contentOrNull ?: return
                scope.launch {
                    try {
                        val att = control.call("session.attach", buildJsonObject { put("session_id", sid) })!!.jsonObject
                        val v = parseView(att["view"]!!.jsonObject)
                        _sessions.update { l -> l.filter { it.sessionId != sid } + v }
                    } catch (e: Exception) { Log.w("RexHub", "attach new: ${e.message}") }
                }
            }
            "session_destroyed" -> {
                val sid = payload?.get("session_id")?.jsonPrimitive?.contentOrNull ?: return
                _sessions.update { l -> l.filter { it.sessionId != sid } }
            }
            "session_event" -> {
                val sid = payload?.get("session_id")?.jsonPrimitive?.contentOrNull ?: return
                val inner = payload["event"]?.jsonObject ?: return
                val itype = inner["type"]?.jsonPrimitive?.contentOrNull
                val ip = inner["payload"] as? JsonObject
                when (itype) {
                    "session_view_changed" -> scope.launch { refreshView(sid, ip?.get("revision")?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) }
                    "session_label_changed" -> _sessions.update { l -> l.map { if (it.sessionId == sid) it.copy(label = ip?.get("label")?.jsonPrimitive?.contentOrNull ?: it.label) else it } }
                    "block_event" -> {
                        val bid = ip?.get("block_id")?.jsonPrimitive?.contentOrNull ?: return
                        val name = ip["name"]?.jsonPrimitive?.contentOrNull ?: return
                        val bp = ip["payload"] as? JsonObject
                        when (name) {
                            "program_status_changed" -> {
                                val id = bp?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                                val rec = bp?.get("record")?.jsonObject
                                if (rec != null) updateLive(bid) { it.copy(statuses = it.statuses + (id to parseStatus(id, rec))) }
                            }
                            "program_status_removed" -> {
                                val ids = (bp?.get("ids") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                                updateLive(bid) { it.copy(statuses = it.statuses - ids.toSet()) }
                            }
                            "title_changed" -> updateLive(bid) { it.copy(title = bp?.get("title")?.jsonPrimitive?.contentOrNull ?: "") }
                            "process_changed" -> {
                                val fg = bp?.get("foreground") as? JsonObject
                                updateLive(bid) {
                                    it.copy(
                                        processName = fg?.get("name")?.jsonPrimitive?.contentOrNull,
                                        processPath = fg?.get("path")?.jsonPrimitive?.contentOrNull,
                                    )
                                }
                            }
                            "pwd_changed" -> updateLive(bid) { it.copy(pwd = bp?.get("pwd")?.jsonPrimitive?.contentOrNull ?: it.pwd) }
                        }
                        onBlockEvent(sid, bid, name, bp, this)
                    }
                }
            }
        }
    }

    // ---- lookups for UI / notifications ----
    fun sessionOf(blockId: String) = _sessions.value.firstOrNull { s -> s.windows.any { w -> w.blocks.any { it.blockId == blockId } } }
    fun windowOf(blockId: String) = _sessions.value.firstNotNullOfOrNull { s -> s.windows.firstOrNull { w -> w.blocks.any { it.blockId == blockId } } }
    fun describe(blockId: String): String {
        val s = sessionOf(blockId); val w = windowOf(blockId)
        return listOfNotNull(s?.label?.ifBlank { null }, w?.label?.ifBlank { null }).joinToString(" › ").ifBlank { "Rex" }
    }

    // ---- input ----
    suspend fun write(sessionId: String, blockId: String, data: String) {
        control.blockCall(sessionId, blockId, "$TERMINAL.write", buildJsonObject { put("data", data) })
    }
}
