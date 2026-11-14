package dev.jwise.rhubarb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jwise.rhubarb.agent.ChatItem
import dev.jwise.rhubarb.agent.ToolCall
import dev.jwise.rhubarb.agent.toolsTitle

/**
 * A Claude Code session as a conversation: your messages as bubbles, Claude's replies as formatted
 * text, and tool calls grouped into rows that expand. Read from the session's transcript (see
 * [dev.jwise.rhubarb.agent.TranscriptFollower]); replies go through the message box below.
 */
@Composable
fun ChatView(items: List<ChatItem>, status: String?, working: Boolean, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    // Anchored at the bottom like a chat app: the newest message sits just above the message box,
    // and new ones keep it in view while you're at the bottom.
    val list = rememberLazyListState()
    val count = items.size + (if (working) 1 else 0)
    LaunchedEffect(count) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides foreground) {
    Box(modifier.background(background)) {
        if (items.isEmpty()) {
            Text(status ?: "No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            if (working) item(key = "working") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Working…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(items.asReversed(), key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item)
                    is ChatItem.Assistant -> SelectionContainer { Markdown(item.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp)) }
                    is ChatItem.Command -> Text(item.text, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    is ChatItem.Note -> Text(item.text, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    is ChatItem.Tools -> ToolsRow(item)
                }
            }
        }
    }
    }
}

@Composable
private fun UserBubble(u: ChatItem.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer {
                Text(if (u.text.isEmpty() && u.images > 0) "[image]" else u.text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ToolsRow(t: ChatItem.Tools) {
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    val failed = t.calls.any { it.error }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable { open = !open }.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(toolsTitle(t.calls), style = MaterialTheme.typography.labelLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Sym.ChevronRight, if (open) "Hide" else "Show", Modifier.size(18.dp).rotate(if (open) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.calls.forEach { ToolCallBox(it) }
            }
        }
    }
}

@Composable
private fun ToolCallBox(c: ToolCall) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.summary, style = MaterialTheme.typography.labelLarge, fontFamily = if (c.name == "Bash") FontFamily.Monospace else null,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            c.result?.takeIf { it.isNotBlank() }?.let { r ->
                SelectionContainer {
                    Text(r, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 14,
                        overflow = TextOverflow.Ellipsis,
                        color = if (c.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Enough Markdown for Claude's replies: headings, paragraphs, bullet and numbered lists, fenced
 * code blocks, tables (shown as monospace), and inline bold, italics, code and links.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { mdBlocks(text) }
    val code = MaterialTheme.colorScheme.surfaceContainerHigh
    val link = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) when (b) {
            is Md.Code -> Surface(color = code, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text(b.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), softWrap = false)
            }
            is Md.Heading -> Text(inline(b.text, code, link), fontWeight = FontWeight.SemiBold,
                style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
            is Md.ListItem -> Row {
                Text(b.marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = (b.indent * 12).dp).width(24.dp))
                Text(inline(b.text, code, link), style = MaterialTheme.typography.bodyLarge)
            }
            is Md.Para -> Text(inline(b.text, code, link), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private sealed interface Md {
    data class Code(val text: String) : Md
    data class Heading(val level: Int, val text: String) : Md
    data class ListItem(val marker: String, val indent: Int, val text: String) : Md
    data class Para(val text: String) : Md
}

private val BULLET = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")

private fun mdBlocks(text: String): List<Md> {
    val out = ArrayList<Md>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Md.Para(para.toString().trim()); para.clear() }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        when {
            l.trimStart().startsWith("```") -> {
                flush()
                val body = StringBuilder(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body.appendLine(lines[i]); i++ }
                out += Md.Code(body.toString().trimEnd())
            }
            l.trimStart().startsWith("|") -> {
                flush()
                val body = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    if (!lines[i].contains(Regex("""^\s*\|[\s:|-]+\|\s*$"""))) body.appendLine(lines[i].trim())
                    i++
                }
                out += Md.Code(body.toString().trimEnd()); continue
            }
            l.startsWith("#") -> { flush(); val lvl = l.takeWhile { it == '#' }.length; out += Md.Heading(lvl, l.drop(lvl).trim()) }
            BULLET.matches(l) -> {
                flush()
                val m = BULLET.find(l)!!
                val mk = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                out += Md.ListItem(mk, m.groupValues[1].length / 2, m.groupValues[3])
            }
            l.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(l.trim()) }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex("""`([^`]+)`|\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\w)""")

private fun inline(s: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(at, m.range.first))
        val (c, b, lt, lu, it) = m.destructured
        when {
            c.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(c) }
            b.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(b) }
            lt.isNotEmpty() -> withLink(LinkAnnotation.Url(lu)) { withStyle(SpanStyle(color = link)) { append(lt) } }
            it.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(it) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}
