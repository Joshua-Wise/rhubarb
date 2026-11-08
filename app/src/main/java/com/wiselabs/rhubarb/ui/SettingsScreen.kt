package com.wiselabs.rhubarb.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Terminal
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
import com.wiselabs.rhubarb.BuildConfig
import com.wiselabs.rhubarb.RexApp
import com.wiselabs.rhubarb.TerminalColors
import com.wiselabs.rhubarb.ThemeMode
import com.wiselabs.rhubarb.service.WatchService
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

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionHeader("Connection")
            GroupItem(0, 3, onClick = { editing = "host" },
                leading = { Icon(Icons.Default.Computer, null) },
                headline = { Text("Host name") },
                supporting = { Text(hostLabel ?: app.defaultHostLabel()) })
            GroupItem(1, 3, onClick = onChangeServer,
                leading = { Icon(Icons.Default.Link, null) },
                headline = { Text("Server address") },
                supporting = { Text(app.serverUrl ?: "Not set") })
            GroupItem(2, 3, onClick = { editing = "device" },
                leading = { Icon(Icons.Default.Badge, null) },
                headline = { Text("This device's name") },
                supporting = { Text("$deviceLabel · how this phone appears in Rex's client list") })

            SectionHeader("Hosts")
            val hosts = rememberKnownHosts()
            hosts.forEachIndexed { i, (label, url) ->
                val current = isCurrentHost(url)
                GroupItem(i, hosts.size,
                    onClick = { if (!current) app.switchServer(url, label) },
                    leading = { Icon(Icons.Default.Dns, null) },
                    headline = { Text(label) },
                    supporting = { Text(if (current) "Connected · ${displayAddress(url)}" else displayAddress(url)) },
                    trailing = {
                        if (current) Icon(Icons.Default.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
                        else if (st.savedHosts.value.any { com.wiselabs.rhubarb.Settings.hostKey(it.second) == com.wiselabs.rhubarb.Settings.hostKey(url) })
                            IconButton(onClick = { st.forgetHost(url) }) { Icon(Icons.Default.Close, "Forget host") }
                    })
            }

            SectionHeader("Notifications")
            GroupItem(0, 3,
                leading = { Icon(Icons.Default.NotificationsActive, null) },
                headline = { Text("Watch in background") },
                supporting = { Text("Keep a connection open so agents that need you can alert this phone") },
                trailing = {
                    Switch(watch, onCheckedChange = {
                        watch = it; app.watchEnabled = it
                        if (it) WatchService.start(ctx) else WatchService.stop(ctx)
                    })
                })
            GroupItem(1, 3,
                leading = { Icon(Icons.Default.DoneAll, null) },
                headline = { Text("When work finishes") },
                supporting = { Text("Notify when a program goes from working to done") },
                trailing = { Switch(notifyDone, onCheckedChange = { notifyDone = it; app.notifyDone = it }) })
            GroupItem(2, 3,
                leading = { Icon(Icons.Default.Sms, null) },
                headline = { Text("Program notifications") },
                supporting = { Text("Show notifications that terminal programs send") },
                trailing = { Switch(notifyPrograms, onCheckedChange = { st.setNotifyPrograms(it) }) })

            SectionHeader("Appearance")
            val dynCount = if (Build.VERSION.SDK_INT >= 31) 3 else 2
            GroupItem(0, dynCount,
                leading = { Icon(Icons.Default.DarkMode, null) },
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
                    leading = { Icon(Icons.Default.Palette, null) },
                    headline = { Text("Dynamic color") },
                    supporting = { Text("Use colors from your wallpaper") },
                    trailing = { Switch(dynamic, onCheckedChange = { st.setDynamicColor(it) }) })
            }
            GroupItem(dynCount - 1, dynCount,
                leading = { Icon(Icons.Default.Terminal, null) },
                headline = { Text("Terminal colors") },
                supporting = {
                    Column {
                        Text(if (termColors == TerminalColors.App) "Light or dark to match this app" else "The colors the Mac's terminal uses")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            listOf(TerminalColors.App to "Match app", TerminalColors.Mac to "Mac's").forEachIndexed { i, (v, label) ->
                                SegmentedButton(
                                    selected = termColors == v, onClick = { st.setTerminalColors(v) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label) }
                            }
                        }
                    }
                })

            SectionHeader("Terminal")
            val renderer by st.renderer.collectAsState()
            val fitToPhone by st.fitToPhone.collectAsState()
            GroupItem(0, 2,
                leading = { Icon(Icons.Default.Terminal, null) },
                headline = { Text("Renderer") },
                supporting = {
                    Column {
                        Text(if (renderer == com.wiselabs.rhubarb.Renderer.Ghostty) "Ghostty, the same terminal engine as Rex's own apps (beta)" else "xterm.js in a web view")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            listOf(com.wiselabs.rhubarb.Renderer.Ghostty to "Ghostty", com.wiselabs.rhubarb.Renderer.Xterm to "xterm.js").forEachIndexed { i, (v, label) ->
                                SegmentedButton(selected = renderer == v, onClick = { st.setRenderer(v) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                            }
                        }
                    }
                })
            GroupItem(1, 2,
                leading = { Icon(Icons.Default.PhoneAndroid, null) },
                headline = { Text("Fit panes to phone") },
                supporting = { Text("Resize a pane to this screen while you have it open. The Mac shows it at the phone's size until you leave.") },
                trailing = { Switch(fitToPhone, onCheckedChange = { st.setFitToPhone(it) }) })

            SectionHeader("About")
            GroupItem(0, 1,
                leading = { Icon(Icons.Default.Info, null) },
                headline = { Text("Rhubarb ${BuildConfig.VERSION_NAME}") },
                supporting = {
                    Text(
                        "An unofficial Android client for Rex by Superlogical, built on the API every Rex server " +
                            "documents at /llms.txt. Terminal rendering by Ghostty (MIT) via chuchu (MIT), or xterm.js (MIT).",
                    )
                })
        }
    }

    editing?.let { which ->
        val isHost = which == "host"
        var text by remember(which) { mutableStateOf(if (isHost) hostLabel ?: app.defaultHostLabel() else deviceLabel) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isHost) "Host name" else "This device's name") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    supportingText = { Text(if (isHost) "Shown at the top of the home screen" else "Other Rex clients see this name") },
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
