package dev.jwise.rhubarb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.jwise.rhubarb.RexApp
import dev.jwise.rhubarb.rex.ConnState
import dev.jwise.rhubarb.rex.RexControl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** First run: nothing to go back to, so the form gets a whole screen with a little explanation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onSaved: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Connect to Rex") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Your Mac's Rex server is reachable over Tailscale. Install Tailscale on this phone and " +
                    "sign in with the same account, then add the server. On the Mac, `rex server status` " +
                    "shows its address on the https: line.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ServerForm(initialUrl = "", onSaved = onSaved)
        }
    }
}

/** Add a server (or change the current one) from a sheet over Home or Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(title: String, initialUrl: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 20.dp))
            ServerForm(initialUrl = initialUrl, onSaved = onSaved)
        }
    }
}

/**
 * Address (with a QR button in the field), optional name, Connect. Tests the address with a
 * throwaway connection and only saves it once that works.
 */
@Composable
private fun ServerForm(initialUrl: String, onSaved: () -> Unit) {
    val app = RexApp.instance
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (url.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            val c = RexControl(RexControl.normalizeBase(url), app.scope, "Rhubarb (setup)")
            c.start()
            val st = withTimeoutOrNull(12_000) { c.state.first { it is ConnState.Connected || it is ConnState.Failed } }
            c.stop()
            when (st) {
                is ConnState.Connected -> {
                    val sameServer = RexControl.normalizeBase(url) == app.serverUrl?.let { RexControl.normalizeBase(it) }
                    when {
                        name.isNotBlank() -> app.settings.setHostLabel(name.trim())
                        !sameServer -> app.settings.setHostLabel(null)
                    }
                    app.serverUrl = url.trim()
                    app.settings.rememberHost(app.settings.hostLabel.value ?: app.defaultHostLabel(), url.trim())
                    busy = false
                    onSaved()
                    return@launch
                }
                is ConnState.Failed -> error = "Couldn't connect: ${st.reason}"
                else -> error = "Timed out. Is Tailscale on and connected on this phone?"
            }
            busy = false
        }
    }

    // Rex's own pairing code (`rex server tailscale pair`) is just the server's address.
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.trim()?.takeIf { it.isNotEmpty() }?.let { url = it; connect() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it; error = null },
            label = { Text("Address") },
            placeholder = { Text("my-mac-rex.tailnet-name.ts.net") },
            trailingIcon = {
                IconButton(enabled = !busy, onClick = {
                    scan.launch(ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the code from \"rex server tailscale pair\" on your Mac")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false))
                }) { Icon(Sym.QrCodeScanner, "Scan QR code") }
            },
            supportingText = {
                Text(error ?: "Tailnet name, host:port or https:// address. Or scan the code from `rex server tailscale pair`.")
            },
            isError = error != null,
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = url.isNotBlank() && !busy,
            onClick = { connect() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 0.dp), strokeWidth = 2.dp)
            else Text("Connect")
        }
    }
}
