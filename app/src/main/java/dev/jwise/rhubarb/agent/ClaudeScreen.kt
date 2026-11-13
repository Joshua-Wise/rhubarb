package dev.jwise.rhubarb.agent

/*
 * Reading Claude Code's screen. The rules (a menu counts only while it's live, answers are checked
 * against a fresh read before a key is pressed, Claude ignores digits for its first ~600 ms) follow
 * chompy by Jeff Arese (https://github.com/jeffarese/chompy, Apache License 2.0). This is a smaller
 * reader of plain screen text written for Rhubarb; see NOTICE.
 */

/** One option of a numbered menu Claude Code shows, such as a permission prompt's "1. Yes". */
data class MenuOption(val number: Int, val label: String, val detail: String?, val selected: Boolean) {
    /** Options that hand the terminal over for typing instead of answering: not offered as buttons. */
    val needsTyping: Boolean
        get() = TYPING.any { label.contains(it, ignoreCase = true) }

    private companion object {
        val TYPING = listOf("Type something", "Chat about this", "tell Claude what to do", "Type here")
    }
}

/** A live numbered menu at the bottom of the screen, with the question above it. */
data class MenuPrompt(val question: String?, val context: List<String>, val options: List<MenuOption>) {
    /** A permission prompt (run this, edit that) rather than one of Claude's own questions. */
    val permission: Boolean
        get() = question?.startsWith("Do you want", ignoreCase = true) == true ||
            options.firstOrNull()?.label?.startsWith("Yes") == true

    /** The options one press answers, in order. */
    val answers: List<MenuOption> get() = options.filterNot { it.needsTyping }

    /** The same question with the same options: safe to answer what was read earlier. */
    fun sameAs(o: MenuPrompt) = question == o.question && options.map { it.number to it.label } == o.options.map { it.number to it.label }
}

/** What the bottom of a Claude Code pane shows. */
data class ClaudeScreen(
    val prompt: MenuPrompt? = null,
    /** The permission mode: "default", "auto", "plan", "accept edits", "manual"… Null if not shown. */
    val mode: String? = null,
    /** The effort level if the screen says it ("high"), else null. */
    val effort: String? = null,
    /** Claude is working and Esc would interrupt it. */
    val working: Boolean = false,
)

object ClaudeScreenReader {
    // "❯ 1. Yes", "  2. No", "│ ❯ 3. Maybe │"; the number is 1-9.
    private val OPTION = Regex("""^(\s*)(❯|›|>)?(\s*)([1-9])\.\s+(.*\S)\s*$""")
    private val CHECKBOX = Regex("""^(\[[ xX✔]]|[☐☒✔])\s""")
    private val MODE = Regex("""(?:⏸|⏵⏵|⏵)\s*([a-z][a-z ]*?)(?: mode)? on\b""")
    private val EFFORT = Regex("""(?:thinking with (\w+) effort|●\s*(\w+)\s*·\s*/effort|@(low|medium|high|xhigh|max)\b)""")
    val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")
    private val SPINNER = Regex("""^[·✢✳✶✻✽*]\s+\S.*…""")

    /** [lines]: the screen's last rows, top to bottom (more history above is fine). */
    fun read(lines: List<String>): ClaudeScreen {
        val rows = lines.map { stripBox(it.trimEnd()) }.dropLastWhile { it.isBlank() }
        val bottom = rows.takeLast(4)
        val mode = bottom.firstNotNullOfOrNull { MODE.find(it)?.groupValues?.get(1)?.trim() }
            ?: if (bottom.any { it.contains("? for shortcuts") }) "default" else null
        val effort = rows.takeLast(12).asReversed().firstNotNullOfOrNull { l ->
            EFFORT.find(l)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.lowercase()?.takeIf { it in EFFORT_LEVELS }
        }
        val working = bottom.any { it.contains("esc to interrupt", ignoreCase = true) } ||
            rows.takeLast(8).any { SPINNER.containsMatchIn(it.trim()) }
        return ClaudeScreen(readMenu(rows), mode, effort, working)
    }

    /** "│ text │" (an older boxed prompt) -> " text". */
    private fun stripBox(l: String): String {
        val t = l.trimEnd()
        return if (t.startsWith("│") && t.endsWith("│") && t.length >= 2) " " + t.substring(1, t.length - 1).trimEnd() else t
    }

    private fun isRule(l: String): Boolean {
        val t = l.trim()
        return t.length >= 8 && (t.startsWith("──") || t.startsWith("╌╌") || t.startsWith("╭") || t.startsWith("╰"))
    }

    private fun indent(l: String) = l.length - l.trimStart().length

    private fun readMenu(rows: List<String>): MenuPrompt? {
        var i = rows.lastIndex
        // Below a live menu there's at most a few lines of key hints ("Esc to cancel · Tab to amend").
        val tail = StringBuilder()
        var tailLines = 0
        while (i >= 0 && OPTION.matchEntire(rows[i]) == null) {
            if (rows[i].isNotBlank()) {
                if (++tailLines > 3) return null
                tail.insert(0, rows[i] + " ")
            }
            i--
        }
        if (i < 0) return null
        if (tailLines > 0 && !Regex("(?i)esc|enter|to select|to navigate|cancel").containsMatchIn(tail)) return null

        // Options, bottom up. Indented lines under an option are its wrapped text or description.
        val opts = ArrayList<MenuOption>()
        val detail = ArrayList<String>()
        var numberCol = -1
        while (i >= 0) {
            val r = rows[i]
            val m = OPTION.matchEntire(r)
            if (m != null) {
                val col = m.groupValues[1].length + m.groupValues[2].length + m.groupValues[3].length
                val label = m.groupValues[5]
                if (CHECKBOX.containsMatchIn(label)) return null // multi-select: needs the terminal
                opts.add(0, MenuOption(m.groupValues[4].toInt(), label, detail.joinToString(" ").ifBlank { null }, m.groupValues[2].isNotEmpty()))
                detail.clear(); numberCol = col; i--; continue
            }
            if (r.isBlank() || isRule(r)) {
                // A blank or rule line inside the menu (before "Chat about this"): keep going only if
                // an option with the next lower number is right above.
                var j = i - 1
                while (j >= 0 && (rows[j].isBlank() || isRule(rows[j]))) j--
                val above = if (j >= 0) OPTION.matchEntire(rows[j]) else null
                val next = opts.firstOrNull()?.number
                if (above != null && next != null && above.groupValues[4].toInt() == next - 1) { i = j; continue }
                break
            }
            if (numberCol >= 0 && indent(r) > numberCol) { detail.add(0, r.trim()); i--; continue }
            break
        }
        if (opts.size < 2) return null
        if (opts.map { it.number } != (1..opts.size).toList()) return null
        if (opts.count { it.selected } != 1) return null

        // The question: the text right above the options (skipping one blank line).
        while (i >= 0 && rows[i].isBlank()) i--
        val q = ArrayList<String>()
        while (i >= 0 && rows[i].isNotBlank() && !isRule(rows[i]) && q.size < 3) { q.add(0, rows[i].trim()); i-- }
        // What it's about: up to the rule above the prompt (a command, a file name, a tab label).
        val ctx = ArrayList<String>()
        var guard = 0
        while (i >= 0 && guard++ < 16) {
            val r = rows[i]
            if (isRule(r) && r.trim().startsWith("──")) break
            if (r.isNotBlank() && !isRule(r) && !r.trim().startsWith("Tip:")) ctx.add(0, r.trim().removePrefix("☐ ").trim())
            i--
        }
        return MenuPrompt(q.joinToString(" ").ifBlank { null }, ctx.takeLast(6), opts)
    }
}
