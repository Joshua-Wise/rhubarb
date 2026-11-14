package dev.jwise.rhubarb.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Footers captured from the real agents (Codex 0.167, OpenCode 2.0.21, Copilot CLI 1.0.91). */
class AgentProfilesTest {
    @Test fun codexDefaultAndPlan() {
        val idle = listOf(
            "› Ask Codex to do anything",
            "  GPT-6-Luna default · ~/Library/Caches/rexAndroid/agent-test",
            "  ← for agents · ? for shortcuts                       ⚠ 1 warning · f2 to view",
        )
        AgentProfiles.codex.read(idle).let {
            assertEquals("default", it.mode)
            assertEquals("GPT-6-Luna default", it.model)
            assertFalse(it.working)
        }
        val plan = listOf(
            "› /",
            "  GPT-6-Luna medium · ~/Library/Caches/rexAndroid/agent-test         Plan mode",
            "                                                       ⚠ 1 warning · f2 to view",
        )
        AgentProfiles.codex.read(plan).let {
            assertEquals("plan", it.mode)
            assertEquals("GPT-6-Luna medium", it.model)
        }
    }

    @Test fun openCodeBuildAndPlan() {
        val build = listOf(
            "   ┃  Ask anything… \"Fix a TODO in the codebase\"",
            "   ┃",
            "   ┃  Build · Qwen3.8 27B (local) LM Studio (local)",
            "   ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀",
            "   ~/Library/Caches/rexAndroid/agent-test    shift+tab agents  ctrl+p commands",
        )
        AgentProfiles.openCode.read(build).let {
            assertEquals("build", it.mode)
            assertEquals("Qwen3.8 27B (local) LM Studio (local)", it.model)
        }
        assertEquals("plan", AgentProfiles.openCode.read(build.map { it.replace("Build ·", "Plan ·") }).mode)
    }

    @Test fun copilotModes() {
        val footer = listOf(
            " v1.0.112 downloaded · next· ← open   · Interactive ·    · / commands · ? help",
            "  launch or /restart         sidebar    Manual Approval    · tab next tab",
            " GitHub Copilot • Auto",
        )
        AgentProfiles.copilot.read(footer).let {
            assertEquals("interactive", it.mode)
            assertEquals("Auto", it.model)
        }
        assertEquals("autopilot", AgentProfiles.copilot.read(footer.map { it.replace("Interactive", "Autopilot") }).mode)
    }

    @Test fun workingWhenEscInterrupts() {
        assertTrue(AgentProfiles.codex.read(listOf("• Working (3s • esc to interrupt)", "  GPT-6-Luna default · ~/x")).working)
    }

    @Test fun profilesByProgramName() {
        for (n in listOf("Claude Code", "Codex", "OpenCode", "Copilot CLI", "Gemini CLI")) assertNotNull(n, AgentProfiles.forProgram(n))
        assertNull(AgentProfiles.forProgram("zsh"))
    }
}
