package dev.jwise.rhubarb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.RexApp
import dev.jwise.rhubarb.rex.ConnState
import dev.jwise.rhubarb.rex.RexControl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onSaved: () -> Unit) {
    val app = RexApp.instance
    var url by remember { mutableStateOf(app.serverUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Connect to Rex") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Your Mac's Rex server is reachable over Tailscale. Install Tailscale on this phone, " +
                    "sign in with the same account, then enter the server's address. On the Mac, " +
                    "`rex server status` shows it on the https: line (or `rex server tailscale pair` shows it as a QR code).",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it; result = null },
                label = { Text("Server address") },
                placeholder = { Text("my-mac-rex.tailnet-name.ts.net") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true; result = null
                    scope.launch {
                        // Test with a throwaway connection before saving.
                        val c = RexControl(RexControl.normalizeBase(url), app.scope, "Rhubarb (setup)")
                        c.start()
                        val st = withTimeoutOrNull(12_000) { c.state.first { it is ConnState.Connected || it is ConnState.Failed } }
                        result = when (st) {
                            is ConnState.Connected -> {
                                val who = try {
                                    c.call("server.whoami")?.jsonObject?.get("principal")?.jsonObject?.get("display")?.jsonPrimitive?.contentOrNull
                                } catch (e: Exception) { null }
                                c.stop()
                                if (RexControl.normalizeBase(url) != app.serverUrl?.let { RexControl.normalizeBase(it) }) app.settings.setHostLabel(null)
                                app.serverUrl = url.trim()
                                app.settings.rememberHost(app.settings.hostLabel.value ?: app.defaultHostLabel(), url.trim())
                                busy = false
                                onSaved()
                                "Connected as $who"
                            }
                            is ConnState.Failed -> { c.stop(); "Couldn't connect: ${st.reason}" }
                            else -> { c.stop(); "Timed out. Is Tailscale on and connected on this phone?" }
                        }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp)) ; Text("Connect") }
            result?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
