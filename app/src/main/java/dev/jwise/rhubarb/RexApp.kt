package dev.jwise.rhubarb

import android.app.Application
import android.content.Context
import dev.jwise.rhubarb.rex.RexControl
import dev.jwise.rhubarb.rex.RexHub
import dev.jwise.rhubarb.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RexApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var notifier: Notifier
        private set
    lateinit var settings: Settings
        private set

    private val _hub = MutableStateFlow<RexHub?>(null)
    val hub: StateFlow<RexHub?> = _hub

    /** Block currently open on screen; its notifications are suppressed. */
    @Volatile var visibleBlockId: String? = null

    /** True while Rhubarb's UI is on screen (between the activity's onStart and onStop). */
    @Volatile var foreground = false

    /** Number of reasons to keep the connection open (UI visible, watcher service running). */
    private var holds = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        notifier = Notifier(this)
        notifier.createChannels()
    }

    private val prefs get() = getSharedPreferences("rex", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server", null)
        set(v) {
            prefs.edit().putString("server", v).apply()
            _hub.value?.stop(); _hub.value = null
            if (holds > 0) ensureHub()
        }

    var watchEnabled: Boolean
        get() = prefs.getBoolean("watch", false)
        set(v) { prefs.edit().putBoolean("watch", v).apply() }

    var notifyDone: Boolean
        get() = prefs.getBoolean("notify_done", true)
        set(v) { prefs.edit().putBoolean("notify_done", v).apply() }

    /** Switch to another Rex server, e.g. one from the server's own hosts list. */
    fun switchServer(url: String, label: String?) {
        // Keep the current host in the known list before leaving it.
        serverUrl?.let { settings.rememberHost(settings.hostLabel.value ?: defaultHostLabel(), it) }
        settings.setHostLabel(label ?: settings.savedHosts.value.firstOrNull { Settings.hostKey(it.second) == Settings.hostKey(url) }?.first)
        serverUrl = url
        serverUrl?.let { settings.rememberHost(settings.hostLabel.value ?: defaultHostLabel(), it) }
    }

    /** Known hosts: ones this phone has used, plus the connected server's own hosts list. */
    fun knownHosts(fromServer: List<Pair<String, String>>): List<Pair<String, String>> {
        val cur = serverUrl?.let { (settings.hostLabel.value ?: defaultHostLabel()) to it }
        return (listOfNotNull(cur) + settings.savedHosts.value + fromServer).distinctBy { Settings.hostKey(it.second) }
    }

    /** A readable default name for the server: its Tailscale device name, without "-rex". */
    fun defaultHostLabel(): String =
        (serverUrl ?: "Rex").removePrefix("https://").removePrefix("wss://").substringBefore('.').removeSuffix("-rex")

    @Synchronized fun acquire(): RexHub? { holds++; return ensureHub() }

    @Synchronized fun release() {
        holds = (holds - 1).coerceAtLeast(0)
        if (holds == 0) { _hub.value?.stop(); _hub.value = null }
    }

    @Synchronized private fun ensureHub(): RexHub? {
        _hub.value?.let { return it }
        val url = serverUrl ?: return null
        val h = RexHub(RexControl.normalizeBase(url), scope, "Rhubarb", { settings.deviceLabel.value }) { sid, bid, name, payload, hub ->
            notifier.onBlockEvent(sid, bid, name, payload, hub)
        }
        h.start()
        _hub.value = h
        return h
    }

    companion object {
        lateinit var instance: RexApp
            private set
    }
}
