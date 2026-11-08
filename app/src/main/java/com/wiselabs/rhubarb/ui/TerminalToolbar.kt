package com.wiselabs.rhubarb.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ESC = "\u001b"

/** A sticky modifier: off, for the next key only, or locked on until tapped again. */
enum class ModState { Off, Once, Locked }

/** Ctrl, Alt and Shift for keys from the bar or the phone keyboard. */
@Stable
class KeyMods {
    var ctrl by mutableStateOf(ModState.Off)
    var alt by mutableStateOf(ModState.Off)
    var shift by mutableStateOf(ModState.Off)

    /** Tap: off → next key → locked → off. */
    fun cycle(m: ModState) = when (m) { ModState.Off -> ModState.Once; ModState.Once -> ModState.Locked; ModState.Locked -> ModState.Off }

    /** Apply the active modifiers to [s], then release the one-shot ones. */
    fun apply(s: String): String {
        val out = withMods(s, ctrl != ModState.Off, alt != ModState.Off, shift != ModState.Off)
        if (ctrl == ModState.Once) ctrl = ModState.Off
        if (alt == ModState.Once) alt = ModState.Off
        if (shift == ModState.Once) shift = ModState.Off
        return out
    }

    val any get() = ctrl != ModState.Off || alt != ModState.Off || shift != ModState.Off

    companion object {
        /** Claude Code (and most line editors) take Alt+Enter as "new line, don't send". */
        const val NEW_LINE = "$ESC\r"

        private val csiFinal = Regex("^\u001b\\[([ABCDHF])$")      // arrows, Home, End
        private val csiTilde = Regex("^\u001b\\[(\\d+)~$")        // PgUp, PgDn, Delete…

        fun withMods(s: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String {
            if (!ctrl && !alt && !shift) return s
            // xterm-style modifier parameter: 1 + shift + 2·alt + 4·ctrl.
            val p = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            csiFinal.find(s)?.let { return "$ESC[1;$p${it.groupValues[1]}" }
            csiTilde.find(s)?.let { return "$ESC[${it.groupValues[1]};$p~" }
            var t = s
            if (s == "\t" && shift) t = "$ESC[Z"
            else if (s == "\r" && shift) return NEW_LINE
            else if (s.length == 1) {
                val c = s[0]
                if (shift && c.isLowerCase()) t = c.uppercase()
                if (ctrl) controlChar(t[0])?.let { t = it }
            }
            return if (alt && !t.startsWith(ESC)) ESC + t else t
        }

        private fun controlChar(c: Char): String? = when {
            c.isLetter() -> ((c.uppercaseChar().code - 64).toChar()).toString()
            c == ' ' || c == '@' || c == '2' -> "\u0000"
            c in "[\\]^_" -> ((c.code - 64).toChar()).toString()
            c == '/' -> "\u001f"
            c == '?' -> "\u007f"
            else -> null
        }
    }
}

private val ctrlShortcuts = listOf(
    "C" to "Interrupt", "D" to "End of input", "Z" to "Suspend", "R" to "Search history",
    "L" to "Clear screen", "A" to "Start of line", "E" to "End of line", "W" to "Delete word",
    "U" to "Delete line", "K" to "Delete to end", "J" to "New line", "O" to "Claude Code: transcript",
)

/**
 * The terminal's keys as an M3 Expressive floating toolbar: the keys used constantly (Esc, Ctrl,
 * Tab, arrows, Enter) stay on the bar; "more" opens a panel with the rest.
 *
 * Ctrl, Alt and Shift are sticky: tap for the next key (from the bar or the phone keyboard), tap
 * again to lock, again to release. Hold Ctrl for a menu of common chords. Arrows repeat while
 * held. Hold Enter for a new line that isn't sent (Alt+Enter, which Claude Code understands).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun KeyBar(
    mods: KeyMods, onKey: (String) -> Unit,
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
                    ModChip("Shift", mods.shift) { mods.shift = mods.cycle(mods.shift) }
                    ModChip("Alt", mods.alt) { mods.alt = mods.cycle(mods.alt) }
                    AssistChip(onClick = { onKey(KeyMods.NEW_LINE) }, label = { Text("New line") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, Modifier.size(18.dp)) })
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
                    AssistChip(onClick = { onKeyboard(); more = false }, label = { Text("Keyboard") },
                        leadingIcon = { Icon(Icons.Default.Keyboard, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { onCompose(); more = false }, label = { Text(if (compose) "Close compose" else "Compose") },
                        leadingIcon = { Icon(if (compose) Icons.Default.EditOff else Icons.Default.Edit, null, Modifier.size(18.dp)) })
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        ) {
            IconButton(onClick = { onKey(ESC) }, modifier = Modifier.size(40.dp)) {
                Text("esc", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            CtrlKey(mods, onKey)
            Key(Icons.AutoMirrored.Filled.KeyboardTab, "Tab") { onKey("\t") }
            RepeatKey(Icons.Default.KeyboardArrowLeft, "Left") { onKey("$ESC[D") }
            RepeatKey(Icons.Default.KeyboardArrowUp, "Up") { onKey("$ESC[A") }
            RepeatKey(Icons.Default.KeyboardArrowDown, "Down") { onKey("$ESC[B") }
            RepeatKey(Icons.Default.KeyboardArrowRight, "Right") { onKey("$ESC[C") }
            HoldKey(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter (hold for new line)",
                onClick = { onKey("\r") }, onLongClick = { onKey(KeyMods.NEW_LINE) })
            Key(if (more) Icons.Default.Close else Icons.Default.MoreHoriz, if (more) "Fewer keys" else "More keys") { more = !more }
        }
    }
}

@Composable
private fun ModChip(label: String, state: ModState, onClick: () -> Unit) {
    FilterChip(
        selected = state != ModState.Off, onClick = onClick,
        label = { Text(if (state == ModState.Locked) "$label 🔒" else label) },
    )
}

/** Ctrl: tap to arm (twice to lock); hold for common chords. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CtrlKey(mods: KeyMods, onKey: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (mods.ctrl) {
        ModState.Off -> androidx.compose.ui.graphics.Color.Transparent to LocalContentColor.current
        ModState.Once -> cs.secondaryContainer to cs.onSecondaryContainer
        ModState.Locked -> cs.primary to cs.onPrimary
    }
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(bg)
                .combinedClickable(
                    onClickLabel = "Ctrl for the next key",
                    onLongClickLabel = "Ctrl shortcuts",
                    onClick = { mods.ctrl = mods.cycle(mods.ctrl) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("ctrl", color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                textDecoration = if (mods.ctrl == ModState.Locked) TextDecoration.Underline else null)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((k, what) in ctrlShortcuts) {
                DropdownMenuItem(
                    text = { Text(what) },
                    leadingIcon = { Text("^$k", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold) },
                    onClick = { menu = false; mods.ctrl = ModState.Off; onKey(((k[0].code - 64).toChar()).toString()) },
                )
            }
        }
    }
}

/** A key that repeats while held: once on press, then every 60 ms after 400 ms. */
@Composable
private fun RepeatKey(icon: ImageVector, label: String, onPress: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (pressed) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPress()
                        val repeat = launch { delay(400); while (true) { onPress(); delay(60) } }
                        tryAwaitRelease()
                        repeat.cancel(); pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HoldKey(icon: ImageVector, label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).combinedClickable(
            onClick = onClick,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun Key(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, contentDescription = label) }
}
