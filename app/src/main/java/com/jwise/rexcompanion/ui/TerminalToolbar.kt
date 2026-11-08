package com.jwise.rexcompanion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val ESC = "\u001b"

/**
 * The terminal's keys as an M3 Expressive floating toolbar: the keys used constantly (keyboard,
 * Esc, Tab, arrows, Enter) stay on the bar; "more" opens a panel with the rest.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun KeyBar(
    ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit,
    compose: Boolean, onKeyboard: () -> Unit, onCompose: () -> Unit, onPaste: () -> Unit,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visible = more, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                FlowRow(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FilterChip(selected = ctrl, onClick = onCtrl, label = { Text("Ctrl") })
                    val keys = listOf(
                        "^C" to "\u0003", "^D" to "\u0004", "⇧Tab" to "$ESC[Z", "1" to "1", "2" to "2", "3" to "3",
                        "y" to "y", "n" to "n", "PgUp" to "$ESC[5~", "PgDn" to "$ESC[6~", "Home" to "$ESC[H", "End" to "$ESC[F",
                        "/" to "/", "|" to "|", "~" to "~", "-" to "-", "\\" to "\\",
                    )
                    for ((label, seq) in keys) {
                        AssistChip(onClick = { onKey(seq) }, label = { Text(label, fontFamily = FontFamily.Monospace) })
                    }
                    AssistChip(onClick = onPaste, label = { Text("Paste") },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onCompose(); more = false }, label = { Text(if (compose) "Close compose" else "Compose") },
                        leadingIcon = { Icon(if (compose) Icons.Default.EditOff else Icons.Default.Edit, null, Modifier.size(18.dp)) })
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        ) {
            Key(Icons.Default.Keyboard, "Keyboard", onKeyboard)
            IconButton(onClick = { onKey(ESC) }, modifier = Modifier.size(44.dp)) {
                Text("esc", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Key(Icons.AutoMirrored.Filled.KeyboardTab, "Tab") { onKey("\t") }
            Key(Icons.Default.KeyboardArrowLeft, "Left") { onKey("$ESC[D") }
            Key(Icons.Default.KeyboardArrowUp, "Up") { onKey("$ESC[A") }
            Key(Icons.Default.KeyboardArrowDown, "Down") { onKey("$ESC[B") }
            Key(Icons.Default.KeyboardArrowRight, "Right") { onKey("$ESC[C") }
            Key(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter") { onKey("\r") }
            Key(if (more) Icons.Default.Close else Icons.Default.MoreHoriz, if (more) "Fewer keys" else "More keys") { more = !more }
        }
    }
}

@Composable
private fun Key(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, contentDescription = label) }
}
