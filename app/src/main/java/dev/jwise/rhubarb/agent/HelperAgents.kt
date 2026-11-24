package dev.jwise.rhubarb.agent

/**
 * Works out which agent panes are helpers of another agent, so Home can show them under it. Rex
 * doesn't record which pane started which, so this goes by where they run:
 * - another agent split into the same tab (how agent teams lay out their helpers): the tab's
 *   first agent pane leads, the rest are its helpers;
 * - an agent working in a worktree inside another agent's folder (`<folder>/.claude/worktrees/x`,
 *   `<folder>/.worktrees/x`, `<folder>/worktrees/x`).
 */
object HelperAgents {
    data class PaneInfo(val blockId: String, val sessionId: String, val windowId: String, val agent: Boolean, val cwd: String?)

    private val worktree = Regex("""^(.+?)/(?:\.claude/worktrees|\.worktrees|worktrees)/[^/]+(?:/.*)?$""")

    /** Helper pane → the agent pane it belongs to (always a lead, never another helper). */
    fun parents(panes: List<PaneInfo>): Map<String, String> {
        val agents = panes.filter { it.agent }
        val out = LinkedHashMap<String, String>()
        // Same tab: the first agent pane leads.
        for ((_, inTab) in agents.groupBy { it.sessionId to it.windowId }) {
            val lead = inTab.first()
            for (h in inTab.drop(1)) out[h.blockId] = lead.blockId
        }
        // Worktrees: the agent working in the folder the worktree belongs to.
        for (a in agents) {
            if (a.blockId in out) continue
            val root = a.cwd?.trimEnd('/')?.let { worktree.find(it)?.groupValues?.get(1) } ?: continue
            val lead = agents.firstOrNull { it.blockId != a.blockId && it.sessionId == a.sessionId && it.cwd?.trimEnd('/') == root } ?: continue
            out[a.blockId] = lead.blockId
        }
        // No chains: a helper's helper belongs to the top lead (and nothing leads itself).
        fun top(id: String, seen: Set<String> = emptySet()): String = out[id]?.takeIf { it !in seen }?.let { top(it, seen + id) } ?: id
        return out.keys.associateWith { top(it) }.filter { it.key != it.value }
    }

    /** [items] reordered so each lead's helpers come right after it (helpers whose lead isn't in [items] stay put). */
    fun <T> nest(items: List<T>, id: (T) -> String, parents: Map<String, String>): List<T> {
        val ids = items.map(id).toSet()
        val placed = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (t in items) {
            val tid = id(t)
            if (tid in placed) continue
            if (parents[tid] in ids) continue // comes after its lead
            out += t; placed += tid
            for (h in items) if (parents[id(h)] == tid && placed.add(id(h))) out += h
        }
        for (t in items) if (placed.add(id(t))) out += t
        return out
    }

    /** A Rex pwd ("file://host/path" or a path) as a plain path. */
    fun path(pwd: String?): String? = when {
        pwd == null -> null
        pwd.startsWith("file://") -> "/" + pwd.removePrefix("file://").substringAfter('/')
        else -> pwd
    }
}
