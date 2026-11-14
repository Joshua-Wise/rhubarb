package dev.jwise.rhubarb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.R

/** What a new shell types once it starts. */
data class Starter(val label: String, val command: String?, val icon: Int?)

val starters = listOf(
    Starter("Shell", null, null),
    Starter("Claude Code", "claude", R.drawable.ic_claude),
    Starter("Codex", "codex", R.drawable.ic_codex),
    Starter("OpenCode", "opencode", R.drawable.ic_opencode),
    Starter("Copilot", "copilot", R.drawable.ic_copilot),
    Starter("Gemini", "gemini", R.drawable.ic_gemini),
)

/** The last folder and program chosen, so the next new tab starts there (for this app run). */
private object NewDefaults { var cwd: String? = null; var starter = 0 }

/**
 * New session or tab: a name, the folder to start in (browsed on the Mac with [browse], which
 * returns a directory's absolute path and its subdirectories), and what to start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun NewSheet(
    title: String, detail: String, hint: String, busy: Boolean,
    browse: (suspend (String) -> Pair<String, List<String>>)?,
    onCreate: (name: String, cwd: String?, run: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(NewDefaults.cwd) }
    var starter by remember { mutableIntStateOf(NewDefaults.starter) }
    var home by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    fun pretty(p: String?): String {
        if (p.isNullOrBlank()) return "Home folder"
        val h = home
        return if (h != null && (p == h || p.startsWith("$h/"))) "~" + p.removePrefix(h) else p
    }
    fun create() {
        NewDefaults.cwd = cwd; NewDefaults.starter = starter
        onCreate(name, cwd, starters[starter].command)
    }
    LaunchedEffect(browse) { if (browse != null) home = runCatching { browse("~").first }.getOrNull() }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            if (browsing && browse != null) {
                FolderBrowser(
                    start = cwd ?: "~", browse = browse, pretty = ::pretty,
                    onPick = { cwd = it; browsing = false }, onBack = { browsing = false },
                )
                return@Column
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(128) }, singleLine = true, enabled = !busy,
                label = { Text(hint) }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy) create() }),
            )
            Spacer(Modifier.size(12.dp))
            Surface(
                onClick = { if (browse != null && !busy) browsing = true },
                enabled = browse != null,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = { Icon(Sym.FolderOpen, null) },
                    overlineContent = { Text("Start in") },
                    headlineContent = { Text(pretty(cwd), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (browse != null) Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) },
                )
            }
            Text("Start with", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                starters.forEachIndexed { i, s ->
                    FilterChip(
                        selected = starter == i, onClick = { starter = i }, enabled = !busy,
                        label = { Text(s.label) },
                        leadingIcon = {
                            if (s.icon != null) Icon(painterResource(s.icon), null, Modifier.size(18.dp))
                            else Icon(Sym.Terminal, null, Modifier.size(18.dp))
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { create() }, enabled = !busy) {
                    if (busy) LoadingIndicator(Modifier.size(20.dp)) else Text("Create")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderBrowser(
    start: String,
    browse: suspend (String) -> Pair<String, List<String>>,
    pretty: (String?) -> String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    var path by remember { mutableStateOf(start) }
    var dirs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        dirs = null; error = null
        runCatching { browse(path) }
            .onSuccess { (abs, list) -> path = abs; dirs = list }
            .onFailure { error = it.message ?: "Couldn't list that folder" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Sym.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text("Choose a folder", style = MaterialTheme.typography.titleLarge)
            Text(pretty(path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { path = "~" }) { Icon(Sym.Home, "Home folder") }
        IconButton(onClick = { if (path != "/") path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) {
            Icon(Sym.ArrowUpward, "Up one folder")
        }
        Spacer(Modifier.weight(1f))
        Text("Hidden", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Switch(showHidden, onCheckedChange = { showHidden = it })
    }
    val shown = dirs?.filter { showHidden || !it.startsWith(".") }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
        when {
            error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            shown == null -> item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { LoadingIndicator() } }
            shown.isEmpty() -> item { Text("No folders here", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(shown, key = { it }) { d ->
                ListItem(
                    modifier = Modifier.clickable { path = path.trimEnd('/') + "/" + d },
                    leadingContent = { Icon(Sym.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onBack) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onPick(path) }, enabled = error == null) { Text("Use this folder") }
    }
}
