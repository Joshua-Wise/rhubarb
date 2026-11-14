package dev.jwise.rhubarb.agent

/**
 * What the controls strip offers for each coding agent: how its mode is cycled and read, its
 * model/effort chip, and its common slash commands. Checked against the agents themselves
 * (Codex 0.167, OpenCode 2.0, Copilot CLI 1.0) and Gemini CLI's documentation, November 2026.
 */
data class AgentProfile(
    /** The program name Rhubarb shows for the agent (see programOf). */
    val program: String,
    /** The key that cycles the agent's mode. All four use Shift+Tab. */
    val modeKey: String = "\u001b[Z",
    /** Effort levels picked from a menu and sent as "/effort <level>" (Claude Code only). */
    val effortLevels: List<String>? = null,
    /** A chip that opens the agent's own model (and effort) picker, labelled from the screen. */
    val modelCommand: String? = null,
    val modelChipName: String = "Model",
    /** Slash commands on the strip, in order. */
    val commands: List<String>,
    /** Commands that throw away the conversation, so the strip asks first. */
    val confirm: Set<String> = emptySet(),
    /** Reads the mode, model and working state from the bottom of the screen. */
    val read: (List<String>) -> ClaudeScreen,
)

object AgentProfiles {
    private val esc = Regex("""(?i)esc(ape)? to (interrupt|cancel)|esc interrupt""")
    private fun working(lines: List<String>) = lines.takeLast(8).any { esc.containsMatchIn(it) }

    val claude = AgentProfile(
        program = "Claude Code",
        effortLevels = ClaudeScreenReader.EFFORT_LEVELS,
        commands = listOf("/model", "/compact", "/context", "/usage", "/resume", "/rewind", "/clear"),
        confirm = setOf("/clear"),
        read = { ClaudeScreenReader.read(it) },
    )

    // Footer: "GPT-6-Luna medium · ~/dir" and, in Plan mode, "Plan mode" at the right.
    private val codexFooter = Regex("""^\s*(\S.*?)\s+·\s+[~/]""")
    val codex = AgentProfile(
        program = "Codex",
        modelCommand = "/model",
        commands = listOf("/permissions", "/compact", "/new", "/diff", "/review", "/status", "/resume"),
        confirm = setOf("/new"),
        read = { lines ->
            val bottom = lines.takeLast(4)
            val footer = bottom.lastOrNull { codexFooter.containsMatchIn(it) }
            ClaudeScreen(
                mode = if (footer?.trimEnd()?.endsWith("Plan mode") == true) "plan" else if (footer != null) "default" else null,
                model = footer?.let { codexFooter.find(it)?.groupValues?.get(1)?.trim() },
                working = working(lines),
            )
        },
    )

    // Above the prompt's bottom edge: "Build · Qwen3.8 27B (local) LM Studio (local)".
    private val openCodeAgent = Regex("""^\s*┃?\s*([A-Z][a-zA-Z]+) · (.+?)\s*$""")
    val openCode = AgentProfile(
        program = "OpenCode",
        modelCommand = "/models",
        commands = listOf("/effort", "/new", "/sessions", "/diff", "/review", "/status"),
        confirm = setOf("/new"),
        read = { lines ->
            val m = lines.takeLast(10).lastNotNullOfOrNull { openCodeAgent.find(it) }
            ClaudeScreen(
                mode = m?.groupValues?.get(1)?.lowercase(),
                model = m?.groupValues?.get(2)?.substringBefore("  ")?.trim(),
                working = working(lines),
            )
        },
    )

    // Footer: "· Interactive · … Manual Approval", "Plan · Manual", "Autopilot · …"; model "GitHub Copilot • Auto".
    private val copilotMode = Regex("""\b(Interactive|Plan|Autopilot)\b""")
    private val copilotModel = Regex("""GitHub Copilot\s*•\s*(.+?)\s*$""")
    val copilot = AgentProfile(
        program = "Copilot CLI",
        modelCommand = "/model",
        commands = listOf("/compact", "/new", "/context", "/diff", "/usage", "/undo"),
        confirm = setOf("/new"),
        read = { lines ->
            val bottom = lines.takeLast(6)
            ClaudeScreen(
                mode = bottom.lastNotNullOfOrNull { copilotMode.find(it)?.groupValues?.get(1) }?.lowercase(),
                model = bottom.lastNotNullOfOrNull { copilotModel.find(it)?.groupValues?.get(1) },
                working = working(lines),
            )
        },
    )

    // Not installed here to look at: Shift+Tab cycles default, auto-edit and plan (per its docs),
    // but how its footer names the mode isn't verified, so the chip just says "Mode".
    val gemini = AgentProfile(
        program = "Gemini CLI",
        modelCommand = "/model",
        commands = listOf("/compress", "/stats", "/resume", "/rewind", "/tools", "/clear"),
        confirm = setOf("/clear"),
        read = { lines -> ClaudeScreen(working = working(lines)) },
    )

    private val all = listOf(claude, codex, openCode, copilot, gemini)

    fun forProgram(name: String): AgentProfile? = all.firstOrNull { it.program == name }

    private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(f: (T) -> R?): R? {
        for (i in indices.reversed()) f(this[i])?.let { return it }
        return null
    }
}
