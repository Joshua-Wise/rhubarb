package com.wiselabs.rhubarb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wiselabs.rhubarb.ghostty.TerminalSelection
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wiselabs.rhubarb.ghostty.GhosttyTerminal
import com.wiselabs.rhubarb.ghostty.TerminalCanvas
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Rex pane drawn by Ghostty. Unless the phone owns the pane, the Mac-sized grid is scaled to the
 * screen width (bottom rows kept in view); [zoom] widens the canvas, which then pans sideways.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GhosttyPane(
    ghostty: GhosttyTerminal,
    zoom: Float,
    fitToWidth: Boolean,
    background: String,
    onTap: () -> Unit,
    onWheelInput: (ByteArray) -> Unit,
    onCanvasGrid: (IntArray) -> Unit,
) {
    val snapshot by ghostty.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    val bg = Color(android.graphics.Color.parseColor("#$background"))
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val snap = snapshot
        if (snap == null) {
            ContainedLoadingIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val width = if (fitToWidth) maxWidth * zoom.coerceAtLeast(1f) else maxWidth
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            TerminalCanvas(
                snapshot = snap,
                modifier = Modifier.width(width).fillMaxHeight(),
                fontSizeSp = 13f,
                fitSnapshotToCanvas = fitToWidth,
                fitWidth = true,
                clientSelectionOnly = true,
                selection = selection,
                onSelectionChange = { selection = it },
                onSelectionChanged = { selectedText = it?.text },
                onLinkLongPress = { link = it },
                onTap = onTap,
                onScroll = { delta, x, y ->
                    scope.launch { ghostty.scroll(delta, x, y)?.let(onWheelInput) }
                },
                onResize = { cols, rows, cw, ch, _, _ -> onCanvasGrid(intArrayOf(cols, rows, cw, ch)) },
            )
        }
        link?.let { url -> LinkSheet(url, onDismiss = { link = null }) }
        if (selection != null) {
            Surface(
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val n = snap.codepoints.size
                        if (n > 0) selection = TerminalSelection(0, n - 1)
                    }) {
                        Icon(Icons.Filled.SelectAll, null, Modifier.padding(end = 6.dp)); Text("All")
                    }
                    FilledTonalButton(
                        onClick = {
                            val text = selectedText?.trimEnd()
                            if (!text.isNullOrEmpty()) {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
                            }
                            selection = null
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.padding(end = 6.dp)); Text("Copy")
                    }
                    TextButton(onClick = { selection = null }) { Text("Cancel") }
                }
            }
        }
    }
}

/** Ghostty colors from the app palette, with the Mac's theme (Rex's `theme` message) layered on if given. */
fun ghosttyColors(p: TermPalette, macThemeJson: String?): GhosttyTerminal.Colors {
    var fg = p.fg; var bg = p.bg; var cursor = p.cursor
    val ansi = p.ansi.toMutableList()
    if (macThemeJson != null) runCatching {
        val o = Json.parseToJsonElement(macThemeJson).jsonObject
        fun hex(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.removePrefix("#")
        hex("foreground")?.let { fg = it }; hex("background")?.let { bg = it }; hex("cursor")?.let { cursor = it }
        (o["palette"] as? JsonArray)?.forEach { e ->
            val (i, v) = e.jsonPrimitive.content.split("=", limit = 2)
            i.toIntOrNull()?.takeIf { it in 0..15 }?.let { ansi[it] = v.removePrefix("#") }
        }
    }
    return GhosttyTerminal.colors(fg, bg, cursor, ansi)
}

/** What to do with a link long-pressed in the terminal. */
@Composable
private fun LinkSheet(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val target = if (url.startsWith("www.")) "https://$url" else url
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Link, null) },
        title = { Text("Link") },
        text = { Text(url, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show() }
            }) { Text("Open") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Link", url))
                }) { Text("Copy") }
                TextButton(onClick = {
                    onDismiss()
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, target)
                    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share") }
            }
        },
    )
}
