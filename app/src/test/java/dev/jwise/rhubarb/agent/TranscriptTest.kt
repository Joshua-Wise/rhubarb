package dev.jwise.rhubarb.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class TranscriptTest {
    @Test fun groupsToolCallsAndTitlesThem() {
        val m = ChatModel()
        fun feed(s: String) = m.feed(Json.parseToJsonElement(s).jsonObject)
        feed("""{"t":"user","u":"1","s":"Run the tests"}""")
        feed("""{"t":"assistant","u":"2","p":[{"t":"tool","id":"a","n":"Bash","i":"{\"command\":\"npm test\"}"}]}""")
        feed("""{"t":"user","u":"3","p":[{"t":"result","id":"a","x":"2 failing","e":1}]}""")
        feed("""{"t":"assistant","u":"4","p":[{"t":"tool","id":"b","n":"Read","i":"{\"file_path\":\"/x/App.kt\"}"}]}""")
        feed("""{"t":"assistant","u":"5","p":[{"t":"text","x":"Two tests **fail**."}]}""")
        feed("""{"t":"user","u":"6","s":"<command-name>/model</command-name><command-args>opus</command-args>"}""")
        feed("""{"t":"user","u":"7","meta":1,"s":"hidden"}""")
        val items = m.items()
        assertTrue(items[0] is ChatItem.User)
        val tools = items[1] as ChatItem.Tools
        assertTrue(tools.calls.size == 2 && tools.calls[0].error && tools.calls[0].result == "2 failing")
        assertTrue(toolsTitle(tools.calls) == "Ran 1 command, read 1 file")
        assertTrue(tools.calls[1].summary == "Read App.kt")
        assertTrue((items[2] as ChatItem.Assistant).text.startsWith("Two tests"))
        assertTrue((items[3] as ChatItem.Command).text == "/model opus")
        assertTrue(items.size == 4)
    }

    /** Runs the real helper (sh + perl, as on the Mac) against this machine's own transcripts, if any. */
    @Test fun helperSlimsARealTranscript() {
        val repo = File(System.getProperty("user.dir")).parentFile
        val dir = File(System.getProperty("user.home"), ".claude/projects/" + repo.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-"))
        assumeTrue(File("/usr/bin/perl").exists() && (dir.listFiles()?.any { it.name.endsWith(".jsonl") } == true))
        val p = ProcessBuilder("/bin/sh", "-c", TranscriptFollower.SH.replace("tail -n 6000 -F", "tail -n 400"), "sh", TranscriptFollower.PERL)
            .directory(repo).redirectErrorStream(true).start()
        p.outputStream.write("\n".toByteArray()); p.outputStream.flush()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(30, TimeUnit.SECONDS)
        assertTrue(lines.first(), lines.first().contains("\"ok\":1"))
        val m = ChatModel()
        lines.drop(1).forEach { m.feed(Json.parseToJsonElement(it).jsonObject) }
        val items = m.items()
        println("lines=${lines.size} items=${items.size} kinds=${items.groupingBy { it::class.simpleName }.eachCount()}")
        assertTrue(items.isNotEmpty())
    }
}
