package dev.jwise.rhubarb.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.BuildConfig
import dev.jwise.rhubarb.RexApp
import dev.jwise.rhubarb.TerminalColors
import dev.jwise.rhubarb.ThemeMode
import dev.jwise.rhubarb.service.WatchService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeServer: () -> Unit) {
    val app = RexApp.instance
    val st = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val themeMode by st.themeMode.collectAsState()
    val dynamic by st.dynamicColor.collectAsState()
    val termColors by st.terminalColors.collectAsState()
    val deviceLabel by st.deviceLabel.collectAsState()
    val hostLabel by st.hostLabel.collectAsState()
    val notifyPrograms by st.notifyPrograms.collectAsState()
    var watch by remember { mutableStateOf(app.watchEnabled) }
    var notifyDone by remember { mutableStateOf(app.notifyDone) }
    var editing by remember { mutableStateOf<String?>(null) } // "host" | "device"
    var forgetting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val hub by app.hub.collectAsState()
    var serverInfo by remember { mutableStateOf<Map<String, String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(hub) {
        serverInfo = null
        serverInfo = runCatching { hub?.serverInfo() }.getOrNull().orEmpty()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionHeader("Connection")
            GroupItem(0, 3, onClick = { editing = "host" },
                leading = { Icon(Sym.Computer, null) },
                headline = { Text("Host name") },
                supporting = { Text(hostLabel ?: app.defaultHostLabel()) })
            GroupItem(1, 3, onClick = onChangeServer,
                leading = { Icon(Sym.Link, null) },
                headline = { Text("Server") },
                supporting = { Text(app.serverUrl?.let(::displayAddress) ?: "Not set", maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
            GroupItem(2, 3, onClick = { editing = "device" },
                leading = { Icon(Sym.Badge, null) },
                headline = { Text("Device name") },
                supporting = { Text(deviceLabel) })

            SectionHeader("Hosts")
            val hosts = rememberKnownHosts()
            hosts.forEachIndexed { i, (label, url) ->
                val current = isCurrentHost(url)
                val saved = st.savedHosts.value.any { dev.jwise.rhubarb.Settings.hostKey(it.second) == dev.jwise.rhubarb.Settings.hostKey(url) }
                GroupItem(i, hosts.size,
                    onClick = { if (!current) app.switchServer(url, label) },
                    // Hold a saved host (not the one in use) to forget it.
                    onLongClick = if (saved && !current) ({ forgetting = label to url }) else null,
                    leading = {
                        if (current) Icon(Sym.CheckCircleFilled, "Connected", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Sym.Dns, null)
                    },
                    headline = { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supporting = {
                        Text(displayAddress(url), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    })
            }
            if (hosts.any { (_, u) -> !isCurrentHost(u) }) {
                Text("Hold a host to forget it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
            }

            SectionHeader("Notifications")
            GroupItem(0, 4,
                leading = { Icon(Sym.NotificationsActive, null) },
                headline = { Text("Background") },
                supporting = { Text("Stay connected for alerts") },
                trailing = {
                    Switch(watch, onCheckedChange = {
                        watch = it; app.watchEnabled = it
                        if (it) WatchService.start(ctx) else WatchService.stop(ctx)
                    })
                })
            FlagItem(1, 4, st.liveProgress, Sym.Timelapse, "Live progress", "While an agent works")
            GroupItem(2, 4,
                leading = { Icon(Sym.DoneAll, null) },
                headline = { Text("Finished") },
                supporting = { Text("When an agent is done") },
                trailing = { Switch(notifyDone, onCheckedChange = { notifyDone = it; app.notifyDone = it }) })
            GroupItem(3, 4,
                leading = { Icon(Sym.Sms, null) },
                headline = { Text("Program alerts") },
                supporting = { Text("Sent by terminal programs") },
                trailing = { Switch(notifyPrograms, onCheckedChange = { st.setNotifyPrograms(it) }) })

            SectionHeader("Appearance")
            val dynCount = if (Build.VERSION.SDK_INT >= 31) 3 else 2
            GroupItem(0, dynCount,
                leading = { Icon(Sym.DarkMode, null) },
                headline = { Text("Theme") },
                supporting = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = themeMode == m, onClick = { st.setThemeMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(m.name) }
                        }
                    }
                })
            if (Build.VERSION.SDK_INT >= 31) {
                GroupItem(1, dynCount,
                    leading = { Icon(Sym.Palette, null) },
                    headline = { Text("Dynamic color") },
                    supporting = { Text("Match your wallpaper") },
                    trailing = { Switch(dynamic, onCheckedChange = { st.setDynamicColor(it) }) })
            }
            GroupItem(dynCount - 1, dynCount,
                leading = { Icon(Sym.Terminal, null) },
                headline = { Text("Terminal colors") },
                supporting = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf(TerminalColors.App to "App", TerminalColors.Mac to "Mac").forEachIndexed { i, (v, label) ->
                            SegmentedButton(
                                selected = termColors == v, onClick = { st.setTerminalColors(v) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                })

            SectionHeader("Agents")
            FlagItem(0, 2, st.answerButtons, Sym.TouchApp, "Answer buttons", "Tap to answer prompts")
            FlagItem(1, 2, st.claudeBar, Sym.Tune, "Claude controls", "Mode, effort and commands")

            SectionHeader("Terminal")
            val renderer by st.renderer.collectAsState()
            val fitToPhone by st.fitToPhone.collectAsState()
            FlagItem(0, 5, st.messageBox, Sym.Edit, "Message box", "Type with autocorrect")
            GroupItem(1, 5,
                leading = { Icon(Sym.PhoneAndroid, null) },
                headline = { Text("Fit to phone") },
                supporting = { Text("Resize panes while open") },
                trailing = { Switch(fitToPhone, onCheckedChange = { st.setFitToPhone(it) }) })
            FlagItem(2, 5, st.programClipboard, Sym.ContentCopy, "Clipboard", "Let programs copy to this phone")
            FlagItem(3, 5, st.bellVibrate, Sym.Vibration, "Bell", "Vibrate")
            GroupItem(4, 5,
                leading = { Icon(Sym.Code, null) },
                headline = { Text("Renderer") },
                supporting = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf(dev.jwise.rhubarb.Renderer.Ghostty to "Ghostty", dev.jwise.rhubarb.Renderer.Xterm to "xterm.js").forEachIndexed { i, (v, label) ->
                            SegmentedButton(selected = renderer == v, onClick = { st.setRenderer(v) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                })

            SectionHeader("Server")
            val info = serverInfo
            if (info == null) {
                GroupItem(0, 1, leading = { Icon(Sym.Info, null) }, headline = { Text("Loading…") })
            } else if (info.isEmpty()) {
                GroupItem(0, 1, leading = { Icon(Sym.Info, null) }, headline = { Text("Not connected") })
            } else {
                val rows = info.entries.toList()
                rows.forEachIndexed { i, (k, v) ->
                    GroupItem(i, rows.size,
                        headline = { Text(k) },
                        supporting = { Text(v, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                }
            }

            SectionHeader("About")
            GroupItem(0, 1,
                leading = { Icon(Sym.Info, null) },
                headline = { Text("Rhubarb ${BuildConfig.VERSION_NAME}") },
                supporting = { Text("Unofficial Android client for Rex") })
        }
    }

    forgetting?.let { (label, url) ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget $label?") },
            text = { Text("You can add it again later.") },
            confirmButton = { TextButton(onClick = { st.forgetHost(url); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }

    editing?.let { which ->
        val isHost = which == "host"
        var text by remember(which) { mutableStateOf(if (isHost) hostLabel ?: app.defaultHostLabel() else deviceLabel) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isHost) "Host name" else "Device name") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    supportingText = { Text(if (isHost) "Shown on Home" else "Shown to other Rex clients") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    if (isHost) { st.setHostLabel(text.trim()); app.serverUrl?.let { st.rememberHost(text.trim(), it) } }
                    else { st.setDeviceLabel(text.trim()); scope.launch { app.hub.value?.setLabel(text.trim()) } }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

/** A settings row with a switch bound to a [dev.jwise.rhubarb.Settings.Flag]. */
@Composable
private fun FlagItem(index: Int, count: Int, flag: dev.jwise.rhubarb.Settings.Flag, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    val on by flag.value.collectAsState()
    GroupItem(index, count, onClick = { flag.set(!on) },
        leading = { Icon(icon, null) },
        headline = { Text(title) },
        supporting = { Text(detail) },
        trailing = { Switch(on, onCheckedChange = { flag.set(it) }) })
}
