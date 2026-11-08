package com.jwise.rexcompanion.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.jwise.rexcompanion.MainActivity
import com.jwise.rexcompanion.R
import com.jwise.rexcompanion.RexApp
import com.jwise.rexcompanion.rex.ConnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Rex control connection open while the app is in the
 * background, so agent "needs you" events become notifications. The Rex server has no push
 * channel to phones, so a live connection over Tailscale is the only way to hear events.
 */
class WatchService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val app = application as RexApp
        ServiceCompat.startForeground(this, 1, buildNotification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job == null) {
            app.acquire()
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            job = app.scope.launch {
                app.hub.filterNotNull().flatMapLatest { it.state }.collectLatest { st ->
                    val text = when (st) {
                        is ConnState.Connected -> "Watching ${app.hub.value?.sessions?.value?.size ?: 0} sessions"
                        is ConnState.Connecting -> "Connecting…"
                        is ConnState.Failed -> "Reconnecting: ${st.reason}"
                        ConnState.Idle -> "Idle"
                    }
                    try { NotificationManagerCompat.from(this@WatchService).notify(1, buildNotification(text)) } catch (_: SecurityException) {}
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        (application as RexApp).release()
        super.onDestroy()
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_rex)
        .setContentTitle("Rex Companion")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, WatchService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WatchService::class.java))
    }
}

/** Handles the notification "Reply" action: types the text plus Enter into the terminal. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Allow/Deny send their keys as-is; a typed reply gets Enter.
        val text = intent.getStringExtra(Notifier.EXTRA_KEYS)
            ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.plus("\r")
            ?: return
        val sid = intent.getStringExtra(Notifier.EXTRA_SESSION) ?: return
        val bid = intent.getStringExtra(Notifier.EXTRA_BLOCK) ?: return
        val app = context.applicationContext as RexApp
        val pending = goAsync()
        app.scope.launch {
            val hub = app.acquire()
            try {
                hub?.write(sid, bid, text)
                app.notifier.cancel(bid)
            } catch (_: Exception) {
            } finally {
                app.release(); pending.finish()
            }
        }
    }
}
