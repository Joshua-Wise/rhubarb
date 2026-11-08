package com.jwise.rexcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jwise.rexcompanion.ghostty.GhosttyTerminal
import com.jwise.rexcompanion.ghostty.TerminalCanvas
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
                onTap = onTap,
                onScroll = { delta, x, y ->
                    scope.launch { ghostty.scroll(delta, x, y)?.let(onWheelInput) }
                },
                onResize = { cols, rows, cw, ch, _, _ -> onCanvasGrid(intArrayOf(cols, rows, cw, ch)) },
            )
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
