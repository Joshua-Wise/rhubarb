package com.jwise.rexcompanion

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { System, Light, Dark }
enum class TerminalColors { App, Mac }
enum class Renderer { Ghostty, Xterm }

/** User preferences, exposed as StateFlows so Compose recomposes when they change. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("rex", Context.MODE_PRIVATE)

    private fun <T> flow(v: T) = MutableStateFlow(v)

    private val _themeMode = flow(runCatching { ThemeMode.valueOf(p.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System))
    val themeMode: StateFlow<ThemeMode> = _themeMode
    fun setThemeMode(v: ThemeMode) { p.edit().putString("theme", v.name).apply(); _themeMode.value = v }

    private val _dynamicColor = flow(p.getBoolean("dynamic", true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor
    fun setDynamicColor(v: Boolean) { p.edit().putBoolean("dynamic", v).apply(); _dynamicColor.value = v }

    private val _terminalColors = flow(runCatching { TerminalColors.valueOf(p.getString("term_colors", "App")!!) }.getOrDefault(TerminalColors.App))
    val terminalColors: StateFlow<TerminalColors> = _terminalColors
    fun setTerminalColors(v: TerminalColors) { p.edit().putString("term_colors", v.name).apply(); _terminalColors.value = v }

    private val _deviceLabel = flow(p.getString("device_label", null) ?: "Pixel")
    /** How this phone names itself to Rex (shown in client lists), like the iPad's "iPad". */
    val deviceLabel: StateFlow<String> = _deviceLabel
    fun setDeviceLabel(v: String) { p.edit().putString("device_label", v).apply(); _deviceLabel.value = v }

    private val _hostLabel = flow(p.getString("host_label", null))
    /** The user's name for the current server, e.g. "Work - Mac Mini". */
    val hostLabel: StateFlow<String?> = _hostLabel
    fun setHostLabel(v: String?) { p.edit().putString("host_label", v).apply(); _hostLabel.value = v }

    private val _savedHosts = flow(decodeHosts(p.getString("hosts", null)))
    /** Every Rex server this phone has connected to, as (label, url). */
    val savedHosts: StateFlow<List<Pair<String, String>>> = _savedHosts
    fun rememberHost(label: String, url: String) {
        val key = hostKey(url)
        val list = _savedHosts.value.filter { hostKey(it.second) != key } + (label to url)
        p.edit().putString("hosts", encodeHosts(list)).apply(); _savedHosts.value = list
    }
    fun forgetHost(url: String) {
        val list = _savedHosts.value.filter { hostKey(it.second) != hostKey(url) }
        p.edit().putString("hosts", encodeHosts(list)).apply(); _savedHosts.value = list
    }

    private val _renderer = flow(runCatching { Renderer.valueOf(p.getString("renderer", "Ghostty")!!) }.getOrDefault(Renderer.Ghostty))
    /** Which terminal engine draws panes: Ghostty (native) or xterm.js (WebView). */
    val renderer: StateFlow<Renderer> = _renderer
    fun setRenderer(v: Renderer) { p.edit().putString("renderer", v.name).apply(); _renderer.value = v }

    private val _notifyPrograms = flow(p.getBoolean("notify_programs", true))
    val notifyPrograms: StateFlow<Boolean> = _notifyPrograms
    fun setNotifyPrograms(v: Boolean) { p.edit().putBoolean("notify_programs", v).apply(); _notifyPrograms.value = v }

    companion object {
        /** Compare servers by host name, ignoring scheme and trailing slashes. */
        fun hostKey(url: String) = url.trim().lowercase().substringAfter("://").substringBefore('/').trimEnd('/')
        private fun encodeHosts(l: List<Pair<String, String>>) =
            org.json.JSONArray(l.map { org.json.JSONObject().put("label", it.first).put("url", it.second) }).toString()
        private fun decodeHosts(s: String?): List<Pair<String, String>> = try {
            val a = org.json.JSONArray(s ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("label") to o.getString("url") } }
        } catch (e: Exception) { emptyList() }
    }
}
