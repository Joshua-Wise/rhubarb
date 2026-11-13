package dev.jwise.rhubarb.ui

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.jwise.rhubarb.rex.RexDownload
import dev.jwise.rhubarb.rex.RexHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download a file from the pane's machine: browse from the pane's folder (or type a path), tap a
 * file, then save it to the phone, open it, or share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(h: RexHub, sessionId: String, blockId: String, startPath: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(startPath?.ifBlank { null } ?: "~") }
    var typed by remember { mutableStateOf(path) }
    var listing by remember { mutableStateOf<RexHub.Listing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var got by remember { mutableStateOf<RexDownload.Downloaded?>(null) }

    fun download(p: String) {
        if (job?.isActive == true) return
        fetching = p.substringAfterLast('/'); progress = 0f; error = null
        job = scope.launch {
            try {
                got = RexDownload.fetch(ctx, h.control, sessionId, blockId, p) { progress = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "Couldn't download it" }
            fetching = null
        }
    }

    LaunchedEffect(path) {
        loading = true; error = null
        try {
            val l = h.listDir(sessionId, blockId, path)
            listing = l; typed = l.path
            error = when (l.status) {
                "ok" -> null
                "missing" -> "That folder doesn't exist."
                // A file was typed: download it.
                "not_dir" -> { download(l.path); null }
                "denied" -> "Rex isn't allowed to read that folder. On the Mac, give Rex access in System Settings › Privacy & Security."
                else -> "Couldn't read that folder (${l.status})."
            }
        } catch (e: Exception) { error = e.message ?: "Couldn't list it" }
        loading = false
    }

    val mime = got?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.name.substringAfterLast('.', "").lowercase()) } ?: "application/octet-stream"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val f = got ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.file.inputStream().use { it.copyTo(o) } } }.isSuccess
            }
            android.widget.Toast.makeText(ctx, if (ok) "Saved ${f.name}" else "Couldn't save ${f.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Download a file", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
            val done = got
            if (done != null) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(done.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(done.file.length()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { save.launch(done.name) }) { Icon(Icons.Default.SaveAlt, null); Text("  Save") }
                        OutlinedButton(onClick = { openFile(ctx, done, mime) }) { Icon(Icons.Default.OpenInNew, null); Text("  Open") }
                        OutlinedButton(onClick = { shareFile(ctx, done, mime) }) { Icon(Icons.Default.Share, null); Text("  Share") }
                    }
                    TextButton(onClick = { got = null }) { Text("Download another") }
                }
                return@Column
            }
            fetching?.let { name ->
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Downloading $name…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { job?.cancel(); fetching = null }) { Text("Cancel") }
                }
                return@Column
            }
            OutlinedTextField(
                value = typed, onValueChange = { typed = it },
                label = { Text("Folder or file on the Mac") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { path = typed.trim().ifEmpty { "~" } }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { listing?.path?.let { p -> path = p.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" } } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up a folder")
                }
                IconButton(onClick = { path = "~" }) { Icon(Icons.Default.Home, "Home folder") }
                FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Hidden files") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            val l = listing
            if (l != null && l.status == "ok") {
                val dirs = l.dirs.filter { showHidden || !it.startsWith('.') }
                val files = l.files.filter { showHidden || !it.startsWith('.') }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(dirs, key = { "d:$it" }) { d ->
                        ListItem(
                            headlineContent = { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            modifier = Modifier.clickable { path = l.path.trimEnd('/') + "/" + d },
                        )
                    }
                    items(files, key = { "f:$it" }) { f ->
                        ListItem(
                            headlineContent = { Text(f, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            modifier = Modifier.clickable { download(l.path.trimEnd('/') + "/" + f) },
                        )
                    }
                    if (dirs.isEmpty() && files.isEmpty()) item {
                        Text("Nothing here" + if (!showHidden && (l.dirs.isNotEmpty() || l.files.isNotEmpty())) " but hidden files." else ".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                    if (l.truncated) item {
                        Text("Some entries aren't shown. Type a path to go straight to a file.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

private fun uriFor(ctx: Context, d: RexDownload.Downloaded) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", d.file)

private fun openFile(ctx: Context, d: RexDownload.Downloaded, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, d), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, "Open ${d.name}")) }
    catch (e: Exception) { android.widget.Toast.makeText(ctx, "No app here can open ${d.name}", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun shareFile(ctx: Context, d: RexDownload.Downloaded, mime: String) {
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, d)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share ${d.name}"))
}

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b bytes"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
