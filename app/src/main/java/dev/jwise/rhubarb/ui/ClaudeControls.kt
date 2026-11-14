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
                if (o.selected) androidx.compose.material3.Button(onClick = { onAnswer(o) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { content() }
                else OutlinedButton(onClick = { onAnswer(o) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))) { content() }
            }
        }
    }
}

/**
 * A coding agent's controls above the key bar, from its [dev.jwise.rhubarb.agent.AgentProfile]:
 * its mode (tap cycles it with the agent's own key), effort or model, Stop while it works, and
 * its common slash commands. Commands that throw the conversation away ask first.
 */
@Composable
fun AgentStrip(profile: dev.jwise.rhubarb.agent.AgentProfile, screen: ClaudeScreen?, onKeys: (String) -> Unit, onCommand: (String) -> Unit) {
    var effortMenu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val isClaude = profile.effortLevels != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (screen?.working == true) AssistChip(
            onClick = { onKeys("\u001b") }, label = { Text("Stop") },
            leadingIcon = { Icon(Sym.Stop, null, Modifier.size(AssistChipDefaults.IconSize)) },
            shape = CircleShape, border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, labelColor = MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer),
        )
        AssistChip(
            onClick = { onKeys(profile.modeKey) },
            label = { Text(screen?.mode?.let { modeName(it, isClaude) } ?: "Mode") },
            shape = CircleShape, border = null, colors = tonalChip(),
            leadingIcon = { Icon(Sym.Shield, "Mode, tap for the next", Modifier.size(AssistChipDefaults.IconSize)) },
        )
        profile.effortLevels?.let { levels ->
            Box {
                AssistChip(
                    onClick = { effortMenu = true },
                    label = { Text(screen?.effort?.let { "Effort: $it" } ?: "Effort") },
                    shape = CircleShape, border = null, colors = tonalChip(),
                    leadingIcon = { Icon(Sym.Speed, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
                DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                    levels.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level) },
                            trailingIcon = { if (level == screen?.effort) Icon(Sym.Check, "Current") },
                            onClick = { effortMenu = false; onCommand("/effort $level") },
                        )
                    }
                }
            }
        }
        // Other agents pick model and effort in their own picker; the chip shows what's in use.
        profile.modelCommand?.let { cmd ->
            AssistChip(
                onClick = { onCommand(cmd) },
                label = { Text(screen?.model?.let { if (it.length > 28) it.take(27) + "…" else it } ?: profile.modelChipName,
                    maxLines = 1) },
                shape = CircleShape, border = null, colors = tonalChip(),
                leadingIcon = { Icon(Sym.Speed, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
        profile.commands.forEach { c ->
            AssistChip(onClick = { if (c in profile.confirm) confirming = c else onCommand(c) }, label = { Text(c) },
                shape = CircleShape, border = null, colors = tonalChip())
        }
    }
    confirming?.let { c ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Start over?") },
            text = { Text("$c starts ${profile.program} on a fresh conversation. The old one can usually be picked up again later.") },
            confirmButton = { TextButton(onClick = { confirming = null; onCommand(c) }) { Text(c) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private fun modeName(m: String, claude: Boolean) = when {
    claude && m == "default" -> "Ask first"
    m == "accept edits" -> "Accept edits"
    m == "auto-edit" || m == "auto_edit" -> "Auto-edit"
    else -> m.replaceFirstChar { it.uppercase() }
}

@Composable
private fun tonalChip() = AssistChipDefaults.assistChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingIconContentColor = MaterialTheme.colorScheme.primary,
)
