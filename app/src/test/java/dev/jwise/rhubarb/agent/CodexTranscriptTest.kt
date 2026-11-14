package dev.jwise.rhubarb.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the real Codex helper against a Codex session for a folder on this machine, if one exists. */
class CodexTranscriptTest {
    @Test fun codexSessionBecomesAConversation() {
        val folder = File(System.getProperty("user.home"), "Library/Caches/rexAndroid/codex-chat")
        assumeTrue(folder.isDirectory && File(System.getProperty("user.home"), ".codex/sessions").isDirectory)
        val p = ProcessBuilder("/bin/sh", "-c", CODEX_SH.replace("tail -n 20000 -F", "tail -n 20000"), "sh", CODEX_PERL)
            .directory(folder).redirectErrorStream(true).start()
        p.outputStream.write("\n".toByteArray()); p.outputStream.flush()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(30, TimeUnit.SECONDS)
        lines.forEach { println("  " + it.take(160)) }
        assertTrue(lines.first(), lines.first().contains("\"ok\":1"))
        val m = ChatModel()
        lines.drop(1).forEach { m.feed(Json.parseToJsonElement(it).jsonObject) }
        val items = m.items()
        println(items.joinToString("\n") { "  " + it::class.simpleName + ": " + it.toString().take(140) })
        assertTrue(items.any { it is ChatItem.User } && items.any { it is ChatItem.Assistant } && items.any { it is ChatItem.Tools })
    }
}
