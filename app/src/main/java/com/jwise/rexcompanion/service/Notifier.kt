package com.jwise.rexcompanion.service

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
import com.jwise.rexcompanion.MainActivity
import com.jwise.rexcompanion.R
import com.jwise.rexcompanion.RexApp
import com.jwise.rexcompanion.rex.RexHub
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

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
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_BLOCK = "block_id"
        const val KEY_REPLY = "reply"
        const val EXTRA_KEYS = "keys"
        const val ACTION_REPLY = "com.jwise.rexcompanion.REPLY"
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
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_rex, "Reply", pi).addRemoteInput(ri).build()
    }

    /** A one-tap action that types [keys] into the pane as-is (no Enter added). */
    private fun keysAction(sessionId: String, blockId: String, label: String, keys: String): NotificationCompat.Action {
        val i = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_SESSION, sessionId); putExtra(EXTRA_BLOCK, blockId); putExtra(EXTRA_KEYS, keys)
            data = android.net.Uri.parse("rex://keys/$label/$blockId")
        }
        val pi = PendingIntent.getBroadcast(ctx, (blockId + label).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_rex, label, pi).build()
    }

    private fun post(id: Int, b: NotificationCompat.Builder) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    fun cancel(blockId: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(blockId, 1))
    }

    fun onBlockEvent(sessionId: String, blockId: String, name: String, payload: JsonObject?, hub: RexHub) {
        val app = RexApp.instance
        val onScreen = app.visibleBlockId == blockId
        when (name) {
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
                            .setSmallIcon(R.drawable.ic_stat_rex)
                            .setContentTitle("${pretty(who)} $what")
                            .setContentText(msg.ifBlank { where })
                            .setSubText(where)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.ifBlank { where }))
                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(openIntent(sessionId, blockId))
                            .setAutoCancel(true)
                        // Claude Code's permission menu: "1" picks "Yes"; Esc declines (the
                        // option numbers for "No" vary, Esc always means no).
                        val claudePermission = rec["kind"]?.jsonPrimitive?.contentOrNull == "permission" &&
                            who.contains("claude", ignoreCase = true)
                        if (claudePermission) {
                            b.addAction(keysAction(sessionId, blockId, "Allow", "1"))
                            b.addAction(keysAction(sessionId, blockId, "Deny", "\u001b"))
                        }
                        b.addAction(replyAction(sessionId, blockId))
                        post(notifId(blockId, 1), b)
                    }
                    else -> {
                        if (prev == "blocked") cancel(blockId)
                        if (state == "done" && prev == "working" && app.notifyDone && !onScreen) {
                            post(notifId(blockId, 2), NotificationCompat.Builder(ctx, CH_DONE)
                                .setSmallIcon(R.drawable.ic_stat_rex)
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
                    .setSmallIcon(R.drawable.ic_stat_rex)
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
