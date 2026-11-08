package com.wiselabs.rhubarb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.wiselabs.rhubarb.rex.ProgramStatus

fun statusLabel(s: ProgramStatus?): String? = s?.let {
    when (it.state) {
        "blocked" -> when (it.kind) { "permission" -> "Needs permission"; "question" -> "Has a question"; "auth" -> "Needs password"; else -> "Waiting" }
        "working" -> "Working"
        "done" -> "Done"
        "error" -> "Error"
        else -> null
    }?.plus(it.progress?.let { p -> " · $p%" } ?: "")
}

@Composable
fun StatusDot(state: String?) {
    Box(Modifier.size(8.dp).background(statusColor(state), CircleShape))
}

/**
 * M3 Expressive grouped lists: items in one group share a container with large outer corners,
 * small inner corners, and a 2dp gap between items.
 */
fun groupShape(index: Int, count: Int): Shape {
    val big = 20.dp; val small = 4.dp
    return when {
        count == 1 -> RoundedCornerShape(big)
        index == 0 -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
        index == count - 1 -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
        else -> RoundedCornerShape(small)
    }
}

@Composable
fun GroupItem(
    index: Int, count: Int,
    onClick: (() -> Unit)? = null,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = groupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        ListItem(
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            headlineContent = headline,
            supportingContent = supporting,
            leadingContent = leading,
            trailingContent = trailing,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The program's icon in an M3 Expressive shape; agents get a cookie shape so they stand out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgramAvatar(program: Program, status: ProgramStatus?) {
    val shape = if (program.agent) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Square.toShape()
    val container = program.brandColor
        ?: if (program.agent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (program.brandColor != null) androidx.compose.ui.graphics.Color.White
        else if (program.agent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val state = status?.state
    BadgedBox(badge = {
        if (state != null && state != "idle") Badge(containerColor = statusColor(state))
    }) {
        Box(Modifier.size(40.dp).background(container, shape), contentAlignment = Alignment.Center) {
            if (program.drawable != null) {
                Icon(androidx.compose.ui.res.painterResource(program.drawable), contentDescription = program.name, tint = content, modifier = Modifier.size(22.dp))
            } else {
                Icon(program.icon, contentDescription = program.name, tint = content, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}
