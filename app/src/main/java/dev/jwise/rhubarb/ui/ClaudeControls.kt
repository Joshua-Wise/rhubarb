package dev.jwise.rhubarb.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.agent.ClaudeScreen
import dev.jwise.rhubarb.agent.ClaudeScreenReader
import dev.jwise.rhubarb.agent.MenuOption
import dev.jwise.rhubarb.agent.MenuPrompt

/**
 * Claude Code's question or permission prompt as buttons, above the keys. Each button is checked
 * against the screen again before its number is pressed. Options that need typing ("Type
 * something", "Chat about this") stay in the terminal.
 */
@Composable
fun AnswerCard(p: MenuPrompt, busy: Boolean, onAnswer: (MenuOption) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val about = p.context.lastOrNull()?.takeIf { p.permission }
            if (about != null) Text(about, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(p.question ?: "Claude Code is asking", style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            p.answers.forEach { o ->
                val content: @Composable () -> Unit = {
                    Text("${o.number}. ${o.label}", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                if (o.selected) FilledTonalButton(onClick = { onAnswer(o) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { content() }
                else OutlinedButton(onClick = { onAnswer(o) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

/** Slash commands on the strip. Tapping sends them; /clear asks first. */
private val STRIP_COMMANDS = listOf("/model", "/compact", "/context", "/usage", "/resume", "/rewind", "/clear")

/**
 * Claude Code's controls above the key bar: the permission mode (tap cycles it with Shift+Tab),
 * effort (pick a level), Stop while it works, and common slash commands.
 */
@Composable
fun ClaudeStrip(screen: ClaudeScreen?, onKeys: (String) -> Unit, onCommand: (String) -> Unit) {
    var effortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (screen?.working == true) AssistChip(
            onClick = { onKeys("\u001b") }, label = { Text("Stop") },
            leadingIcon = { Icon(Icons.Default.Stop, null, Modifier.size(AssistChipDefaults.IconSize)) },
            shape = CircleShape, border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, labelColor = MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer),
        )
        AssistChip(
            onClick = { onKeys("\u001b[Z") },
            label = { Text(screen?.mode?.let { modeName(it) } ?: "Mode") },
            shape = CircleShape, border = null, colors = tonalChip(),
            leadingIcon = { Icon(Icons.Default.Shield, "Permission mode, tap for the next", Modifier.size(AssistChipDefaults.IconSize)) },
        )
        Box {
            AssistChip(
                onClick = { effortMenu = true },
                label = { Text(screen?.effort?.let { "Effort: $it" } ?: "Effort") },
                shape = CircleShape, border = null, colors = tonalChip(),
                leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                ClaudeScreenReader.EFFORT_LEVELS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level) },
                        trailingIcon = { if (level == screen?.effort) Icon(Icons.Default.Check, "Current") },
                        onClick = { effortMenu = false; onCommand("/effort $level") },
                    )
                }
            }
        }
        STRIP_COMMANDS.forEach { c ->
            AssistChip(onClick = { if (c == "/clear") confirmClear = true else onCommand(c) }, label = { Text(c) },
                shape = CircleShape, border = null, colors = tonalChip())
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the conversation?") },
        text = { Text("Claude Code starts over with an empty context. The conversation can still be picked up later with /resume.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onCommand("/clear") }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

private fun modeName(m: String) = when (m) {
    "default" -> "Ask first"
    "accept edits" -> "Accept edits"
    "auto" -> "Auto"
    "plan" -> "Plan"
    "manual" -> "Manual"
    else -> m.replaceFirstChar { it.uppercase() }
}

@Composable
private fun tonalChip() = AssistChipDefaults.assistChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingIconContentColor = MaterialTheme.colorScheme.primary,
)
