package dev.jwise.rhubarb.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.jwise.rhubarb.MainActivity
import dev.jwise.rhubarb.R
import dev.jwise.rhubarb.RexApp
import dev.jwise.rhubarb.rex.RexHub
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import dev.jwise.rhubarb.agent.MenuOption
import dev.jwise.rhubarb.agent.MenuPrompt
import dev.jwise.rhubarb.agent.PromptAnswers
import kotlinx.coroutines.launch

/**
 * Turns Rex terminal events into Android notifications:
 *  - program status -> `blocked` (an agent waiting on permission / a question / auth): high priority
 *  - `working` -> `done`: quiet "finished" notice
 *  - OSC 9 / OSC 777 desktop notifications from programs
 */
class Notifier(private val ctx: Context) {
    companion object {
        const val CH_ATTENTION = "attention"
        const val CH_DONE = "done"
        const val CH_PROGRAM = "program"
        const val CH_SERVICE = "service"
        const val CH_WORKING = "working"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_BLOCK = "block_id"
        const val KEY_REPLY = "reply"
        const val EXTRA_KEYS = "keys"
        const val ACTION_REPLY = "dev.jwise.rhubarb.REPLY"
        const val ACTION_ANSWER = "dev.jwise.rhubarb.ANSWER"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_LABELS = "labels"
    }

    private val lastState = ConcurrentHashMap<String, String>()

    fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A program, such as a coding agent, is waiting for permission or an answer"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A program reported it finished working"
        })
        nm.createNotificationChannel(NotificationChannel(CH_PROGRAM, "Program notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifications that terminal programs send (OSC 9 / OSC 777)"
        })
        nm.createNotificationChannel(NotificationChannel(CH_WORKING, "Working", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A live, silent notification while a coding agent works"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Watching Rex", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the app keeps a connection open to watch your sessions"
        })
    }

    private fun notifId(blockId: String, slot: Int) = (blockId.hashCode() * 31) + slot

    fun openIntent(sessionId: String, blockId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION, sessionId); putExtra(EXTRA_BLOCK, blockId)
            data = android.net.Uri.parse("rex://block/$blockId") // unique per block
        }
        return PendingIntent.getActivity(ctx, blockId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun replyAction(sessionId: String, blockId: String): NotificationCompat.Action {
        val i = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_SESSION, sessionId); putExtra(EXTRA_BLOCK, blockId)
            data = android.net.Uri.parse("rex://reply/$blockId")
        }
        val pi = PendingIntent.getBroadcast(ctx, blockId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Type into terminal (Enter is added)").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_rhubarb, "Reply", pi).addRemoteInput(ri).build()
    }

    /** A button that answers a menu option, checked against the screen again before it's pressed. */
    private fun answerAction(sessionId: String, blockId: String, p: MenuPrompt, o: MenuOption): NotificationCompat.Action {
        val i = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_SESSION, sessionId); putExtra(EXTRA_BLOCK, blockId)
            putExtra(EXTRA_NUMBER, o.number); putExtra(EXTRA_QUESTION, p.question)
            putStringArrayListExtra(EXTRA_LABELS, ArrayList(p.options.map { it.label }))
            data = android.net.Uri.parse("rex://answer/${o.number}/$blockId")
        }
        val pi = PendingIntent.getBroadcast(ctx, (blockId + "#" + o.number).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = o.label.let { if (it.length > 28) it.take(27) + "…" else it }
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_rhubarb, label, pi).build()
    }

    /** Update the "needs you" notification after a button was tapped, e.g. when the menu had changed. */
    fun answerOutcome(sessionId: String, blockId: String, text: String) {
        post(notifId(blockId, 1), NotificationCompat.Builder(ctx, CH_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_rhubarb)
            .setContentTitle(RexApp.instance.hub.value?.describe(blockId) ?: "Rex")
            .setContentText(text)
            .setContentIntent(openIntent(sessionId, blockId))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true))
    }

    private fun post(id: Int, b: NotificationCompat.Builder) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    fun cancel(blockId: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(blockId, 1))
    }

    /** When each working pane started, for the notification's timer. */
    private val workingSince = ConcurrentHashMap<String, Long>()

    /**
     * The live "working" notification for [blockId]: silent and ongoing, with the program's progress
     * (or an indeterminate bar) and a timer. Android 16 shows it as a chip in the status bar.
     */
    private fun postWorking(sessionId: String, blockId: String, hub: RexHub, who: String, msg: String) {
        val app = RexApp.instance
        if (!app.settings.liveProgress.value.value || app.visibleBlockId == blockId) return
        val since = workingSince.getOrPut(blockId) { System.currentTimeMillis() }
        val pct = hub.live.value[blockId]?.progress
        // (ProgressStyle has its own apply(), so no Kotlin apply {} here.)
        val style = NotificationCompat.ProgressStyle()
        if (pct != null && pct >= 0) style.setProgress(pct) else style.setProgressIndeterminate(true)
        val short = pretty(who).substringBefore(' ')
        post(notifId(blockId, 4), NotificationCompat.Builder(ctx, CH_WORKING)
            .setSmallIcon(R.drawable.ic_stat_rhubarb)
            .setContentTitle("${pretty(who)} is working")
            .setContentText(msg.ifBlank { hub.describe(blockId) })
            .setSubText(hub.describe(blockId).takeIf { msg.isNotBlank() })
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(since).setShowWhen(true).setUsesChronometer(true)
            .setShortCriticalText(if (pct != null && pct >= 0) "$pct%" else short)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent(sessionId, blockId)))
    }

    private fun cancelWorking(blockId: String) {
        workingSince.remove(blockId)
        NotificationManagerCompat.from(ctx).cancel(notifId(blockId, 4))
    }

    /**
     * Drop "working" notifications for panes that stopped working, closed, or vanished without a
     * status change (a tab closed mid-task). Runs whenever the hub's sessions or statuses change.
     */
    fun reconcileWorking(hub: RexHub) {
        val live = hub.live.value
        val present = hub.sessions.value.flatMap { s -> s.windows.flatMap { w -> w.blocks.map { it.blockId } } }.toSet()
        for (bid in workingSince.keys.toList()) {
            if (bid !in present || live[bid]?.top?.state != "working" || RexApp.instance.visibleBlockId == bid) cancelWorking(bid)
        }
    }

    /** The connection is going away, so nothing can keep these up to date. */
    fun cancelAllWorking() { workingSince.keys.toList().forEach(::cancelWorking) }

    fun onBlockEvent(sessionId: String, blockId: String, name: String, payload: JsonObject?, hub: RexHub) {
        val app = RexApp.instance
        val onScreen = app.visibleBlockId == blockId
        when (name) {
            "progress_report" -> hub.live.value[blockId]?.top?.takeIf { it.state == "working" }?.let { top ->
                postWorking(sessionId, blockId, hub, top.app ?: "A program", top.msg ?: top.title ?: "")
            }
            "child_exited" -> cancelWorking(blockId)
            "program_status_changed" -> {
                val recId = payload?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                val rec = payload?.get("record")?.jsonObject ?: return
                val state = rec["state"]?.jsonPrimitive?.contentOrNull ?: "idle"
                val key = "$blockId/$recId"
                val prev = lastState.put(key, state)
                if (prev == state) return
                val who = rec["effective_app"]?.jsonPrimitive?.contentOrNull ?: rec["app"]?.jsonPrimitive?.contentOrNull ?: "A program"
                val msg = rec["msg"]?.jsonPrimitive?.contentOrNull ?: rec["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val where = hub.describe(blockId)
                if (state == "working") postWorking(sessionId, blockId, hub, who, msg) else cancelWorking(blockId)
                when (state) {
                    "blocked" -> {
                        if (onScreen) return
                        val what = when (rec["kind"]?.jsonPrimitive?.contentOrNull) {
                            "permission" -> "needs permission"
                            "question" -> "has a question"
                            "auth" -> "needs a password"
                            else -> "is waiting for you"
                        }
                        val b = NotificationCompat.Builder(ctx, CH_ATTENTION)
                            .setSmallIcon(R.drawable.ic_stat_rhubarb)
                            .setContentTitle("${pretty(who)} $what")
                            .setContentText(msg.ifBlank { where })
                            .setSubText(where)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.ifBlank { where }))
                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(openIntent(sessionId, blockId))
                            .setAutoCancel(true)
                        b.addAction(replyAction(sessionId, blockId))
                        post(notifId(blockId, 1), b)
                        // Claude Code: read its menu off the screen and offer the options as buttons.
                        if (who.contains("claude", ignoreCase = true) && app.settings.answerButtons.value.value) {
                            app.scope.launch {
                                val p = PromptAnswers.awaitPrompt(hub, sessionId, blockId) ?: return@launch
                                if (lastState[key] != "blocked") return@launch
                                val answers = p.answers.take(3)
                                if (answers.isEmpty()) return@launch
                                val body = (p.context + listOfNotNull(p.question)).joinToString("\n").ifBlank { msg.ifBlank { where } }
                                val nb = NotificationCompat.Builder(ctx, CH_ATTENTION)
                                    .setSmallIcon(R.drawable.ic_stat_rhubarb)
                                    .setContentTitle("${pretty(who)} $what")
                                    .setContentText(p.question ?: msg.ifBlank { where })
                                    .setSubText(where)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setContentIntent(openIntent(sessionId, blockId))
                                    .setOnlyAlertOnce(true)
                                    .setAutoCancel(true)
                                answers.forEach { nb.addAction(answerAction(sessionId, blockId, p, it)) }
                                if (answers.size < 3) nb.addAction(replyAction(sessionId, blockId))
                                post(notifId(blockId, 1), nb)
                            }
                        }
                    }
                    else -> {
                        if (prev == "blocked") cancel(blockId)
                        if (state == "done" && prev == "working" && app.notifyDone && !onScreen) {
                            post(notifId(blockId, 2), NotificationCompat.Builder(ctx, CH_DONE)
                                .setSmallIcon(R.drawable.ic_stat_rhubarb)
                                .setContentTitle("${pretty(who)} finished")
                                .setContentText(msg.ifBlank { where })
                                .setSubText(where)
                                .setContentIntent(openIntent(sessionId, blockId))
                                .setAutoCancel(true))
                        }
                    }
                }
            }
            "desktop_notification" -> {
                if (onScreen || !app.settings.notifyPrograms.value) return
                val title = payload?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = payload?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                post(notifId(blockId, 3), NotificationCompat.Builder(ctx, CH_PROGRAM)
                    .setSmallIcon(R.drawable.ic_stat_rhubarb)
                    .setContentTitle(title.ifBlank { hub.describe(blockId) })
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSubText(hub.describe(blockId))
                    .setContentIntent(openIntent(sessionId, blockId))
                    .addAction(replyAction(sessionId, blockId))
                    .setAutoCancel(true))
            }
        }
    }

    private fun pretty(app: String) = when (app) {
        "claude-code" -> "Claude Code"
        else -> app.replaceFirstChar { it.uppercase() }
    }
}
