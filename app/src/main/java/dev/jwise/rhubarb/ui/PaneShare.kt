package dev.jwise.rhubarb.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.jwise.rhubarb.rex.RexHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share a pane's screen and scrollback as plain text. Short output goes as text; long output as
 * a .txt file, since an Intent can't carry much.
 */
suspend fun sharePaneText(ctx: Context, h: RexHub, sessionId: String, blockId: String, title: String) {
    val text = h.paneText(sessionId, blockId)
    if (text.isBlank()) {
        android.widget.Toast.makeText(ctx, "Nothing to share yet", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title)
    if (text.length <= 50_000) {
        send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    } else {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val safe = title.replace(Regex("[^A-Za-z0-9._ -]+"), "-").trim().ifBlank { "terminal" }.take(60)
            val f = File(dir, "$safe.txt").apply { writeText(text) }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }
        send.setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
