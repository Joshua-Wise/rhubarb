package dev.jwise.rhubarb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.rex.RexUpload

/** A small status pill for images being sent to the Mac. */
@Composable
fun UploadPill(p: RexUpload.Progress, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (p) {
        is RexUpload.Progress.Failed -> cs.errorContainer to cs.onErrorContainer
        RexUpload.Progress.Done -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.surfaceContainerHigh to cs.onSurface
    }
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = bg, contentColor = fg, shadowElevation = 6.dp) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (p) {
                is RexUpload.Progress.Sending -> {
                    if (p.fraction <= 0f) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    else CircularProgressIndicator(progress = { p.fraction }, modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    Text(
                        if (p.count > 1) "Sending ${p.index + 1} of ${p.count}" else if (p.fraction >= 1f) "Finishing…" else "Sending",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                RexUpload.Progress.Done -> {
                    Icon(Sym.CheckCircle, null, Modifier.size(18.dp))
                    Text("Sent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp, top = 10.dp, bottom = 10.dp))
                }
                is RexUpload.Progress.Failed -> {
                    Icon(Sym.ErrorOutline, null, Modifier.size(18.dp))
                    Text(p.message, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (p != RexUpload.Progress.Done) {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Sym.Close, if (p is RexUpload.Progress.Failed) "Dismiss" else "Cancel", Modifier.size(18.dp))
                }
            }
        }
    }
}
