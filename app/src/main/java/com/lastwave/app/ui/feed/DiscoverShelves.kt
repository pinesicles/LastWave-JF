package com.lastwave.app.ui.feed

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.network.DataMeter
import com.lastwave.app.data.requests.DnAlbum
import com.lastwave.app.ui.library.CoverCard
import com.lastwave.app.ui.library.FindKind
import com.lastwave.app.ui.library.FindQuery
import com.lastwave.app.ui.library.LocalFindOnServer
import com.lastwave.app.ui.library.OutsideActionSheet
import com.lastwave.app.ui.library.SheetAction
import com.lastwave.app.ui.requests.LocalRequestAlbum
import com.lastwave.app.ui.requests.RequestsViewModel

/**
 * "Discover" shelves for the Feed, from your request server's Discover page: albums you don't have yet.
 * Tap one for the menu: request it, check your server for it, or browse the artist's other albums.
 * Nothing is fetched while the data saver is on, or when no request server is connected.
 */
@Composable
internal fun DiscoverShelves(vm: RequestsViewModel = hiltViewModel(key = "requests-global")) {
    val config by vm.config.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val saving by DataMeter.saving.collectAsStateWithLifecycle()
    LaunchedEffect(config != null, saving) { if (!saving) vm.loadDiscover() }
    if (config == null || shelves.isEmpty()) return

    val context = LocalContext.current
    val request = LocalRequestAlbum.current
    val find = LocalFindOnServer.current
    var menuFor by remember { mutableStateOf<DnAlbum?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shelves.forEach { shelf ->
            Column {
                Text(
                    shelf.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(shelf.albums, key = { i, a -> "${shelf.title}_${i}_${a.mbid}" }) { _, a ->
                        CoverCard(
                            title = a.title, subtitle = a.artist.ifBlank { null }, coverUrl = a.coverUrl, round = false,
                            onClick = { menuFor = a }, onLongClick = { menuFor = a },
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
    }
    menuFor?.let { a ->
        OutsideActionSheet(
            title = a.title,
            subtitle = listOfNotNull(a.artist.ifBlank { null }, a.year?.toString()).joinToString(" • "),
            coverUrl = a.coverUrl,
            round = false,
            actions = buildList {
                add(
                    SheetAction(
                        Icons.Filled.CloudDownload,
                        if (a.requested) "Already requested" else "Request album",
                        if (a.requested) "It's on its way" else "Adds the whole album to your library",
                    ) {
                        if (!a.requested) vm.requestDirect(a) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    },
                )
                add(SheetAction(Icons.Filled.Search, "Find on server", "Check if it's already in your library") {
                    find(FindQuery(FindKind.ALBUM, a.title, a.artist))
                })
                if (a.artist.isNotBlank()) {
                    add(SheetAction(Icons.Filled.Person, "More by ${a.artist} on server", "Browse their other albums to request") { request(a.artist) })
                }
            },
            onDismiss = { menuFor = null },
            badge = "Discover",
        )
    }
}
