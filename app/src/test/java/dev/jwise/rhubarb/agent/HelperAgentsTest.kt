package dev.jwise.rhubarb.agent

import dev.jwise.rhubarb.agent.HelperAgents.PaneInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class HelperAgentsTest {
    @Test fun sameTabAndWorktrees() {
        val panes = listOf(
            PaneInfo("lead", "s", "w1", true, "/Users/me/app"),
            PaneInfo("shell", "s", "w1", false, "/Users/me/app"),
            PaneInfo("mate", "s", "w1", true, "/Users/me/app"),
            PaneInfo("wt", "s", "w2", true, "/Users/me/app/.claude/worktrees/fix-login"),
            PaneInfo("wt2", "s", "w3", true, "/Users/me/app/.worktrees/docs/src"),
            PaneInfo("other", "s", "w4", true, "/Users/me/site"),
            PaneInfo("elsewhere", "t", "w5", true, "/Users/me/app/.worktrees/x"),
        )
        val p = HelperAgents.parents(panes)
        assertEquals(mapOf("mate" to "lead", "wt" to "lead", "wt2" to "lead"), p)
        val order = HelperAgents.nest(listOf("wt", "other", "lead", "mate"), { it }, p)
        assertEquals(listOf("other", "lead", "wt", "mate"), order)
        assertEquals("/Users/me/app", HelperAgents.path("file://mac.local/Users/me/app"))
    }
}
