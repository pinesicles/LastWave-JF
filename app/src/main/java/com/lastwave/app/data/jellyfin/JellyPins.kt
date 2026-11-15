package com.lastwave.app.data.jellyfin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Albums, playlists and artists you've pinned; they sort to the top of their lists. */
@Singleton
class JellyPins @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val key = stringSetPreferencesKey("jf_pins")

    val pins: Flow<Set<String>> = dataStore.data.map { it[key].orEmpty() }.distinctUntilChanged()

    suspend fun toggle(id: String) {
        dataStore.edit { p ->
            val now = p[key].orEmpty()
            p[key] = if (id in now) now - id else now + id
        }
    }
}
