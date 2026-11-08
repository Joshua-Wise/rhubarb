package com.wiselabs.rhubarb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wiselabs.rhubarb.RexApp
import com.wiselabs.rhubarb.Settings

/** The hosts this phone knows, with the connected one first. Reads the server's own list too. */
@Composable
fun rememberKnownHosts(): List<Pair<String, String>> {
    val app = RexApp.instance
    val hub by app.hub.collectAsState()
    val saved by app.settings.savedHosts.collectAsState()
    val label by app.settings.hostLabel.collectAsState()
    var fromServer by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(hub) { fromServer = hub?.hosts() ?: emptyList() }
    return remember(saved, label, fromServer, app.serverUrl) { app.knownHosts(fromServer) }
}

fun isCurrentHost(url: String) = RexApp.instance.serverUrl?.let { Settings.hostKey(it) == Settings.hostKey(url) } == true

fun displayAddress(url: String) = url.substringAfter("://").trimEnd('/')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSheet(onDismiss: () -> Unit, onAddServer: () -> Unit) {
    val app = RexApp.instance
    val hosts = rememberKnownHosts()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Hosts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
            hosts.forEachIndexed { i, (label, url) ->
                val current = isCurrentHost(url)
                GroupItem(
                    i, hosts.size,
                    onClick = { if (!current) app.switchServer(url, label); onDismiss() },
                    leading = { Icon(Icons.Default.Computer, null) },
                    headline = { Text(label) },
                    supporting = { Text(displayAddress(url)) },
                    trailing = { if (current) Icon(Icons.Default.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { onDismiss(); onAddServer() }) {
                    Icon(Icons.Default.Add, null); Text("  Add server")
                }
            }
        }
    }
}
