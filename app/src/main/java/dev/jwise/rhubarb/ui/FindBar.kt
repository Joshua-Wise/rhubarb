package dev.jwise.rhubarb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Find in scrollback. Matches are counted from the bottom (1 is the newest); the up arrow and
 * the keyboard's search key move to older matches, the down arrow to newer ones.
 */
@Composable
fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    position: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("Find in output") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            suffix = {
                if (query.isNotEmpty()) Text(
                    if (count == 0) "No matches" else "$position of $count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onOlder() }),
        )
        IconButton(onClick = onOlder, enabled = count > 1) { Icon(Icons.Default.KeyboardArrowUp, "Older match") }
        IconButton(onClick = onNewer, enabled = count > 1) { Icon(Icons.Default.KeyboardArrowDown, "Newer match") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close find") }
    }
}
