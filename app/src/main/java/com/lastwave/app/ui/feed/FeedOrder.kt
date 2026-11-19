package com.lastwave.app.ui.feed

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One block on the Jellyfin Feed. [id] is stable, [title] is what the rearrange dialog shows. */
internal class FeedSection(val id: String, val title: String, val content: @Composable () -> Unit)

/** The order and visibility of Feed shelves, kept per device. Unknown/new shelves go after the ones you've placed. */
internal object FeedOrder {
    private fun prefs(c: Context) = c.getSharedPreferences("jw_feed_order", Context.MODE_PRIVATE)

    fun order(c: Context): List<String> = prefs(c).getString("order", "")!!.split('|').filter { it.isNotEmpty() }
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet()) ?: emptySet()

    fun save(c: Context, order: List<String>, hidden: Set<String>) {
        prefs(c).edit().putString("order", order.joinToString("|")).putStringSet("hidden", hidden.toSet()).apply()
    }

    fun reset(c: Context) = prefs(c).edit().clear().apply()

    /** All sections in your order, including hidden ones (the dialog needs them to unhide). */
    fun arrange(c: Context, sections: List<FeedSection>): List<FeedSection> {
        val saved = order(c)
        val byId = sections.associateBy { it.id }
        return saved.mapNotNull { byId[it] } + sections.filter { it.id !in saved }
    }
}

@Composable
internal fun RearrangeFeedDialog(sections: List<FeedSection>, onClose: () -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf(sections.map { it.id }) }
    var hidden by remember { mutableStateOf(FeedOrder.hidden(context)) }
    val titles = remember(sections) { sections.associate { it.id to it.title } }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in order.indices) return
        order = order.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rearrange home") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, id -> id }) { i, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            titles[id].orEmpty(), Modifier.weight(1f), maxLines = 1,
                            color = if (id in hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { hidden = if (id in hidden) hidden - id else hidden + id }) {
                            Icon(if (id in hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide")
                        }
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { move(i, 1) }, enabled = i < order.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { FeedOrder.save(context, order, hidden); onClose() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { FeedOrder.reset(context); onClose() }) { Text("Reset") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}
