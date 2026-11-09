package com.wiselabs.rhubarb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Surface
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wiselabs.rhubarb.RexApp
import com.wiselabs.rhubarb.rex.ConnState
import com.wiselabs.rhubarb.rex.TERMINAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class Pane(val sessionId: String, val windowId: String, val blockId: String, val session: String, val window: String, val label: String, val multi: Boolean)

/** What a rename dialog is renaming: a window when [windowId] is set, else the session. */
private data class Rename(val sessionId: String, val windowId: String?, val current: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpen: (String, String) -> Unit, onSettings: () -> Unit, onAddServer: () -> Unit) {
    val app = RexApp.instance
    val hub by app.hub.collectAsState()
    val sessions by (hub?.sessions ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val live by (hub?.live ?: remember { MutableStateFlow(emptyMap()) }).collectAsState()
    val conn by (hub?.state ?: remember { MutableStateFlow(ConnState.Idle) }).collectAsState()
    val hostLabel by app.settings.hostLabel.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showHosts by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = rememberSaveable { mutableStateOf(setOf<String>()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // null: no dialog; "": new session; a session ID: new window in that session.
    var creating by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Rename?>(null) }
    fun rename(r: Rename, name: String) {
        val h = hub ?: return
        busy = true
        scope.launch {
            try {
                if (r.windowId != null) h.renameWindow(r.sessionId, r.windowId, name) else h.renameSession(r.sessionId, name)
                renaming = null
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Couldn't rename it: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    fun create(target: String, name: String) {
        val h = hub ?: return
        busy = true
        scope.launch {
            try {
                if (target.isEmpty()) { val (sid, bid) = h.createSession(name); creating = null; onOpen(sid, bid) }
                else { val bid = h.newWindow(target, name); creating = null; onOpen(target, bid) }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Couldn't create it: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    val panes = sessions.flatMap { s ->
        s.windows.flatMap { w ->
            val terms = w.blocks.filter { it.creator == TERMINAL }
            terms.map { b -> Pane(s.sessionId, w.windowId, b.blockId, s.label, w.label, b.label, terms.size > 1) }
        }
    }
    val attention = panes.filter { live[it.blockId]?.top?.state == "blocked" }
    val title = hostLabel ?: app.defaultHostLabel()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                // Search app bar: a pill that opens full-screen search, with Settings beside it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { searching = true },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text("Search $title", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.Default.Settings, "Settings") }
                }
                // Host (tap to switch) and connection state.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp).clip(MaterialTheme.shapes.small).clickable { showHosts = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    val (dot, text) = when (conn) {
                        is ConnState.Connected -> "done" to "${sessions.size} sessions · ${panes.size} panes"
                        is ConnState.Connecting -> "working" to "Connecting…"
                        is ConnState.Failed -> "error" to "Offline"
                        ConnState.Idle -> null to "Idle"
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, "Switch host")
                    Spacer(Modifier.width(8.dp))
                    StatusDot(dot); Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        floatingActionButton = {
            if (conn is ConnState.Connected) {
                MediumFloatingActionButton(onClick = { creating = "" }) { Icon(Icons.Default.Add, "New session", Modifier.size(28.dp)) }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; hub?.refreshAll(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
                if (conn is ConnState.Failed && sessions.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CloudOff, null)
                                Text("Can't reach $title", style = MaterialTheme.typography.titleMedium)
                                Text((conn as ConnState.Failed).reason, style = MaterialTheme.typography.bodyMedium)
                                Text("Check that Tailscale is on, on this phone and the Mac.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (sessions.isEmpty() && conn !is ConnState.Connected) {
                    item { Centered { ContainedLoadingIndicator() } }
                }

                if (attention.isNotEmpty()) {
                    item { SectionHeader("Needs you") }
                    itemsIndexed(attention, key = { _, p -> "a" + p.blockId }) { _, p ->
                        val l = live[p.blockId]; val top = l?.top; val prog = programOf(l)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProgramAvatar(prog, top)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${prog.name} · ${statusLabel(top)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("${p.session} › ${p.window}", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                top?.msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Button(onClick = { onOpen(p.sessionId, p.blockId) }) { Text("Open") }
                                }
                            }
                        }
                    }
                }

                for (s in sessions) {
                    val sPanes = panes.filter { it.sessionId == s.sessionId }
                    val isCollapsed = s.sessionId in collapsed.value
                    item(key = "h" + s.sessionId) {
                        val rot by animateFloatAsState(if (isCollapsed) -90f else 0f, label = "chevron")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { collapsed.value = if (isCollapsed) collapsed.value - s.sessionId else collapsed.value + s.sessionId },
                                onLongClickLabel = "Rename session",
                                onLongClick = { renaming = Rename(s.sessionId, null, s.label) },
                            ).padding(start = 32.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        ) {
                            Text(s.label.ifBlank { "Session" }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            val waiting = sPanes.count { live[it.blockId]?.top?.state == "blocked" }
                            if (waiting > 0 && isCollapsed) {
                                StatusDot("blocked"); Spacer(Modifier.width(6.dp))
                                Text("$waiting waiting", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(8.dp))
                            }
                            IconButton(onClick = { creating = s.sessionId }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, "New window in ${s.label.ifBlank { "this session" }}", Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ExpandMore, if (isCollapsed) "Expand" else "Collapse", modifier = Modifier.rotate(rot))
                        }
                    }
                    if (!isCollapsed) {
                        itemsIndexed(sPanes, key = { _, p -> p.blockId }) { i, p ->
                            val l = live[p.blockId]; val top = l?.top; val prog = programOf(l)
                            GroupItem(
                                index = i, count = sPanes.size,
                                onClick = { onOpen(p.sessionId, p.blockId) },
                                onLongClick = { renaming = Rename(p.sessionId, p.windowId, p.window) },
                                leading = { ProgramAvatar(prog, top) },
                                headline = {
                                    Text(p.window + if (p.multi) " · ${p.label}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supporting = {
                                    val line = top?.msg ?: l?.title?.ifBlank { null } ?: l?.pwd?.removePrefix("file://")?.substringAfter("/Users/")?.let { "~/" + it.substringAfter('/') }
                                    line?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                },
                                trailing = {
                                    statusLabel(top)?.let {
                                        Text(it, style = MaterialTheme.typography.labelMedium, color = statusColor(top?.state))
                                    }
                                },
                            )
                        }
                    }
                }
                if (sessions.isEmpty() && conn is ConnState.Connected) {
                    item { Centered { Text("No sessions on $title.") } }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    creating?.let { target ->
        val session = sessions.firstOrNull { it.sessionId == target }?.label?.ifBlank { null }
        NewDialog(
            title = if (target.isEmpty()) "New session" else "New window",
            hint = if (target.isEmpty()) "Session name" else "Window name",
            detail = if (target.isEmpty()) "Starts a shell on $title. Leave the name blank and Rex picks one."
                else "Adds a shell to ${session ?: "this session"} without switching the Mac to it.",
            busy = busy,
            onCreate = { create(target, it) },
            onDismiss = { if (!busy) creating = null },
        )
    }

    renaming?.let { r ->
        NewDialog(
            title = if (r.windowId != null) "Rename window" else "Rename session",
            hint = if (r.windowId != null) "Window name" else "Session name",
            detail = "Shows on every Rex client. Leave it blank to go back to Rex's default name.",
            busy = busy, initial = r.current, confirm = "Rename", icon = Icons.Default.Edit,
            onCreate = { rename(r, it) },
            onDismiss = { if (!busy) renaming = null },
        )
    }

    if (showHosts) HostSheet(onDismiss = { showHosts = false }, onAddServer = onAddServer)

    if (searching) {
        val q = query.trim().lowercase()
        val results = if (q.isEmpty()) emptyList() else panes.filter { p ->
            val l = live[p.blockId]
            listOfNotNull(p.session, p.window, p.label, l?.title, l?.pwd, l?.top?.msg, l?.top?.app, programOf(l).name)
                .any { it.lowercase().contains(q) }
        }
        fun close() { searching = false; query = "" }
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query, onQueryChange = { query = it }, onSearch = {},
                    expanded = true, onExpandedChange = { if (!it) close() },
                    placeholder = { Text("Search sessions and panes") },
                    leadingIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                )
            },
            expanded = true,
            onExpandedChange = { if (!it) close() },
        ) {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                if (q.isNotEmpty() && results.isEmpty()) item { Centered { Text("No matches") } }
                itemsIndexed(results, key = { _, p -> "s" + p.blockId }) { i, p ->
                    val l = live[p.blockId]; val top = l?.top
                    GroupItem(
                        i, results.size,
                        onClick = { close(); onOpen(p.sessionId, p.blockId) },
                        leading = { ProgramAvatar(programOf(l), top) },
                        headline = { Text(p.window + if (p.multi) " · ${p.label}" else "") },
                        supporting = { Text(listOfNotNull(p.session, top?.msg ?: l?.title?.ifBlank { null }).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailing = { statusLabel(top)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = statusColor(top?.state)) } },
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewDialog(
    title: String, hint: String, detail: String, busy: Boolean,
    onCreate: (String) -> Unit, onDismiss: () -> Unit,
    initial: String = "", confirm: String = "Create", icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Add,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(128) }, singleLine = true, enabled = !busy,
                    label = { Text(if (initial.isEmpty()) "$hint (optional)" else hint) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (!busy) onCreate(name) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = !busy) {
                if (busy) LoadingIndicator(Modifier.size(20.dp)) else Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
