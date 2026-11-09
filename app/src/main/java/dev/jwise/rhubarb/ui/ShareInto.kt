package dev.jwise.rhubarb.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.RexApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Text and/or images another app shared to Rhubarb. */
data class IncomingShare(val text: String?, val images: List<Pair<Uri, String?>>) {
    val summary: String
        get() = buildList {
            if (images.isNotEmpty()) add(if (images.size == 1) "1 image" else "${images.size} images")
            text?.let { add("“" + it.lineSequence().first().take(60) + if (it.length > 60 || it.contains('\n')) "…”" else "”") }
        }.joinToString(" and ")

    companion object {
        /** The share in [i], or null if [i] isn't a share Rhubarb can use. */
        fun from(i: Intent?): IncomingShare? {
            if (i == null) return null
            val images = when (i.action) {
                Intent.ACTION_SEND -> listOfNotNull(i.stream()?.let { it to i.type?.takeIf { t -> t.startsWith("image/") && t != "image/*" } })
                Intent.ACTION_SEND_MULTIPLE -> i.streams().map { it to null }
                else -> return null
            }.filter { i.type?.startsWith("image/") == true }
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            return if (text == null && images.isEmpty()) null else IncomingShare(text, images)
        }

        @Suppress("DEPRECATION")
        private fun Intent.stream(): Uri? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun Intent.streams(): List<Uri> =
            (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
    }
}

/**
 * Shares on their way into a pane: [waiting] holds one until the user picks a pane, then it's
 * handed to that pane's terminal screen, which takes it once it's open.
 */
object ShareInbox {
    val waiting = MutableStateFlow<IncomingShare?>(null)
    private val forBlock = ConcurrentHashMap<String, IncomingShare>()
    private val _deliveries = MutableStateFlow(0)
    /** Bumps whenever a share is handed to a pane, so an already-open pane notices. */
    val deliveries: StateFlow<Int> = _deliveries

    fun deliver(blockId: String, share: IncomingShare) {
        forBlock[blockId] = share
        waiting.value = null
        _deliveries.value++
    }

    fun take(blockId: String): IncomingShare? = forBlock.remove(blockId)
}

/** Pick the pane a share goes to: every tab of every session, like Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(share: IncomingShare, onPick: (sessionId: String, blockId: String) -> Unit, onDismiss: () -> Unit) {
    val app = RexApp.instance
    val hub by app.hub.collectAsState()
    val sessions = hub?.sessions?.collectAsState()?.value.orEmpty()
    val live = hub?.live?.collectAsState()?.value.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Send to a tab", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                share.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    if (hub == null) "Not connected to Rex." else "No sessions yet. Start one first.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                sessions.forEach { s ->
                    val tabs = s.windows.mapNotNull { w ->
                        val b = w.blocks.firstOrNull { it.blockId == w.focusedBlockId } ?: w.blocks.firstOrNull()
                        b?.let { w to it }
                    }
                    if (tabs.isEmpty()) return@forEach
                    item(key = "h:" + s.sessionId) {
                        Text(
                            s.label.ifBlank { "Session" }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    itemsIndexed(tabs, key = { _, t -> t.second.blockId }) { i, (w, b) ->
                        val l = live[b.blockId]
                        GroupItem(
                            index = i, count = tabs.size,
                            onClick = { onPick(s.sessionId, b.blockId) },
                            leading = { ProgramAvatar(programOf(l), l?.top) },
                            headline = { Text(w.label.ifBlank { "Tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supporting = { (l?.title?.ifBlank { null })?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        )
                    }
                }
            }
        }
    }
}
