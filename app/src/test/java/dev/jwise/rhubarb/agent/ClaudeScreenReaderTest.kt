package dev.jwise.rhubarb.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeScreenReaderTest {
    private fun screen(name: String): ClaudeScreen {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        return ClaudeScreenReader.read(text.lines())
    }

    @Test fun bashPermission() {
        val p = assertNotNull(screen("claude-permission-bash").prompt).let { screen("claude-permission-bash").prompt!! }
        assertEquals("Do you want to proceed?", p.question)
        assertTrue(p.permission)
        assertEquals(listOf(1, 2, 3, 4), p.options.map { it.number })
        assertEquals("Yes", p.options[0].label)
        assertTrue(p.options[0].selected)
        assertEquals("No", p.options[3].label)
        assertTrue(p.options[1].detail!!.contains("from this project"))
        assertTrue(p.context.any { it == "date > out.txt" })
        assertTrue(p.context.none { it.startsWith("Tip:") })
        assertEquals(4, p.answers.size)
    }

    @Test fun narrowBashPermission() {
        val p = screen("claude-permission-bash-narrow").prompt
        assertNotNull(p)
        assertEquals("Yes", p!!.options.first().label)
    }

    @Test fun writePermission() {
        val p = screen("claude-permission-write").prompt!!
        assertEquals("Do you want to create hello.txt?", p.question)
        assertEquals(listOf("Yes", "Yes, and switch to accept edits", "No"), p.options.map { it.label })
        assertTrue(p.options[1].detail!!.contains("auto-approve"))
        assertTrue(p.context.contains("hello.txt"))
    }

    @Test fun claudeQuestion() {
        val p = screen("claude-ask-colour").prompt!!
        assertEquals("Which of these three colours do you prefer?", p.question)
        assertFalse(p.permission)
        assertEquals(listOf("Red", "Green", "Blue", "Type something.", "Chat about this"), p.options.map { it.label })
        assertTrue(p.options[0].detail!!.startsWith("A bold, warm colour"))
        // Typing options take the terminal, so they're not buttons.
        assertEquals(listOf("Red", "Green", "Blue"), p.answers.map { it.label })
        assertTrue(p.context.contains("Colour"))
    }

    @Test fun multiSelectAndPreviewNeedTheTerminal() {
        assertNull(screen("claude-ask-multi").prompt)
        assertNull(screen("claude-ask-preview-phone").prompt)
    }

    @Test fun noMenuOnOrdinaryScreens() {
        for (n in listOf("claude-idle-manual", "claude-reply-bullets", "claude-working", "claude-after-no", "claude-streaming", "shell-yn"))
            assertNull(n, screen(n).prompt)
    }

    @Test fun modes() {
        assertEquals("manual", screen("claude-idle-manual").mode)
        assertEquals("auto", screen("claude-idle-auto").mode)
        assertEquals("plan", screen("claude-idle-plan").mode)
        assertEquals("accept edits", screen("claude-idle-accept-edits-narrow").mode)
    }

    @Test fun effortAndWorking() {
        assertEquals("high", screen("claude-effort-high").effort)
        assertEquals("medium", screen("claude-working").effort)
        assertTrue(screen("claude-working").working)
        assertFalse(screen("claude-idle-manual").working)
    }

    @Test fun sameAsComparesQuestionAndOptions() {
        val a = screen("claude-permission-bash").prompt!!
        assertTrue(a.sameAs(a.copy(context = emptyList())))
        assertFalse(a.sameAs(screen("claude-permission-write").prompt!!))
    }
}
