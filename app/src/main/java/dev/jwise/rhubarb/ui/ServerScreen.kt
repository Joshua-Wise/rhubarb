package dev.jwise.rhubarb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

/**
 * First run: a welcome with what Rhubarb does and the three steps to get going, then the server
 * form in a sheet ("Connect your Mac") or the camera straight away ("Scan pairing code").
 */
@Composable
fun ServerScreen(onSaved: () -> Unit) {
    var sheet by remember { mutableStateOf<Boolean?>(null) } // null: closed; true: scan first
    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 28.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(56.dp))
                AppIcon(Modifier.size(88.dp))
                Spacer(Modifier.height(28.dp))
                Text("Rex terminals\non the go.", style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text("Reach your Rex terminals on the go: new sessions, agent updates, and more.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(36.dp))
                Step(1, "Run Rex.", "Download and start Rex Server from Superlogical.")
                Step(2, "Connect this device.", "Connect via Tailscale, VPN, or local IP.")
                Step(3, "Add your server.", "Scan the Rex QR code or enter the URL manually.")
                Spacer(Modifier.height(24.dp))
            }
            Button(onClick = { sheet = false }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Connect your Mac", style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { sheet = true }) {
                    Icon(Sym.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Scan pairing code", style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = {
                    // A pretend Mac inside the app: look around before setting anything up.
                    val app = RexApp.instance
                    app.settings.setHostLabel("Demo Mac")
                    app.serverUrl = dev.jwise.rhubarb.rex.Demo.URL
                    onSaved()
                }) { Text("Try the demo", style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
    sheet?.let { scanFirst ->
        AddServerSheet("Connect your Mac", "", onDismiss = { sheet = null }, onSaved = { sheet = null; onSaved() }, scanFirst = scanFirst)
    }
}

/** The launcher icon (its colour and foreground layer, cropped to the visible part) as a rounded square. */
@Composable
private fun AppIcon(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(androidx.compose.ui.res.colorResource(dev.jwise.rhubarb.R.color.ic_launcher_background))) {
        Image(painterResource(dev.jwise.rhubarb.R.mipmap.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun Step(n: Int, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.padding(top = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // `backticks` mark a command, set in monospace.
            val text = androidx.compose.ui.text.buildAnnotatedString {
                detail.split('`').forEachIndexed { i, part ->
                    if (i % 2 == 1) pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface))
                    append(part)
                    if (i % 2 == 1) pop()
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Add a server (or change the current one) from a sheet over Home or Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(title: String, initialUrl: String, onDismiss: () -> Unit, onSaved: () -> Unit, scanFirst: Boolean = false) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 20.dp))
            ServerForm(initialUrl = initialUrl, onSaved = onSaved, scanFirst = scanFirst)
        }
    }
}

/** An address that would connect without encryption (http/ws to anything but a Tailscale name, which WireGuard encrypts). */
private fun unencrypted(url: String): Boolean {
    val u = url.trim().lowercase()
    val plain = u.startsWith("http://") || u.startsWith("ws://")
    return plain && !u.substringAfter("://").substringBefore('/').substringBefore(':').endsWith(".ts.net")
}

/**
 * Address (with a QR button in the field), optional name, Connect. Tests the address with a
 * throwaway connection and only saves it once that works.
 */
@Composable
private fun ServerForm(initialUrl: String, onSaved: () -> Unit, scanFirst: Boolean = false) {
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

    fun startScan() = scan.launch(ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Scan the code from \"rex server tailscale pair\" on your Mac")
        .setBeepEnabled(false)
        .setOrientationLocked(false))
    androidx.compose.runtime.LaunchedEffect(Unit) { if (scanFirst) startScan() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it; error = null },
            label = { Text("Address") },
            placeholder = { Text("my-mac-rex.tailnet-name.ts.net") },
            trailingIcon = {
                IconButton(enabled = !busy, onClick = { startScan() }) { Icon(Sym.QrCodeScanner, "Scan QR code") }
            },
            supportingText = {
                Text(error ?: if (unencrypted(url)) "Not encrypted: others on this network could see what you type and read."
                    else "Tailnet name, host:port or https:// address. Or scan the code from rex server tailscale pair.")
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
