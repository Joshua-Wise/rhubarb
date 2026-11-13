package dev.jwise.rhubarb.agent

import android.os.SystemClock
import dev.jwise.rhubarb.rex.RexHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads and answers Claude Code's menus over the control connection, whether or not the pane is
 * open. An answer goes only to the prompt that was read: the screen is read again first and
 * nothing is pressed if the question or its options changed (after chompy's PanePrompts; see
 * NOTICE).
 */
object PromptAnswers {
    enum class Result { ANSWERED, UNCONFIRMED, CHANGED, UNREACHABLE, BUSY }

    /** Claude Code ignores digits typed in the first ~600 ms its menu shows. */
    private const val ARM_MS = 650L
    private const val CONFIRM_MS = 2_500L

    private val answering = ConcurrentHashMap.newKeySet<String>()
    /** When each pane's current menu was first seen, keyed by block and question. */
    private val firstSeen = ConcurrentHashMap<String, Long>()

    suspend fun read(h: RexHub, sid: String, blockId: String): ClaudeScreen? = try {
        ClaudeScreenReader.read(h.screenTail(sid, blockId)).also { s ->
            s.prompt?.let { firstSeen.putIfAbsent(blockId + "\u0000" + it.question, SystemClock.elapsedRealtime()) }
        }
    } catch (e: Exception) { null }

    /** Read until a menu shows (an agent may report "blocked" just before drawing it), up to [timeoutMs]. */
    suspend fun awaitPrompt(h: RexHub, sid: String, blockId: String, timeoutMs: Long = 4_000): MenuPrompt? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                read(h, sid, blockId)?.prompt?.let { return@withTimeoutOrNull it }
                delay(700)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    /** Press option [number] of [asked], if the pane still shows that same menu. */
    suspend fun answer(h: RexHub, sid: String, blockId: String, asked: MenuPrompt, number: Int): Result {
        if (!answering.add(blockId)) return Result.BUSY
        try {
            val now = read(h, sid, blockId) ?: return Result.UNREACHABLE
            val cur = now.prompt?.takeIf { it.sameAs(asked) } ?: return Result.CHANGED
            if (cur.answers.none { it.number == number }) return Result.CHANGED
            val seen = firstSeen[blockId + "\u0000" + cur.question] ?: SystemClock.elapsedRealtime()
            val wait = seen + ARM_MS - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            try { h.write(sid, blockId, number.toString()) } catch (e: Exception) { return Result.UNREACHABLE }
            val gone = withTimeoutOrNull(CONFIRM_MS) {
                do { delay(400); val after = read(h, sid, blockId) } while (after == null || after.prompt?.sameAs(asked) == true)
                true
            }
            if (gone == true) firstSeen.remove(blockId + "\u0000" + cur.question)
            return if (gone == true) Result.ANSWERED else Result.UNCONFIRMED
        } finally { answering.remove(blockId) }
    }
}
