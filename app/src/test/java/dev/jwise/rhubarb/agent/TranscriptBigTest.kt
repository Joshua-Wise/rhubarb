package dev.jwise.rhubarb.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Feeds a large real transcript (this machine's own, if present) through the helper and the model. */
class TranscriptBigTest {
    @Test fun realTranscriptKeysAreUnique() {
        val repo = File(System.getProperty("user.dir")).parentFile
        val dir = File(System.getProperty("user.home"), ".claude/projects/" + repo.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-"))
        assumeTrue(dir.listFiles()?.any { it.name.endsWith(".jsonl") } == true)
        val p = ProcessBuilder("/bin/sh", "-c", TranscriptFollower.SH.replace("tail -n 6000 -F", "tail -n 6000"), "sh", TranscriptFollower.PERL)
            .directory(repo).redirectErrorStream(true).start()
        p.outputStream.write("\n".toByteArray()); p.outputStream.flush()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(60, TimeUnit.SECONDS)
        val m = ChatModel()
        lines.drop(1).forEach { m.feed(Json.parseToJsonElement(it).jsonObject) }
        val items = m.items()
        val dupes = items.groupingBy { it.key }.eachCount().filter { it.value > 1 }
        println("lines=${lines.size} bytes=${lines.sumOf { it.length }} items=${items.size} dupes=${dupes.size} ${dupes.keys.take(5)}")
        val maxLine = lines.maxOf { it.length }
        println("longest line=$maxLine")
    }
}
