package com.lastwave.app.data.jellyfin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class RecentCollection(
    val id: String,
    /** "album" or "playlist" (the same words the downloads use). */
    val type: String,
    val name: String,
    val subtitle: String?,
    val count: Int,
    val last: Long,
)

/** Albums and playlists you've played from, with how often and when. Feeds the Feed's tile area. */
@Singleton
class JellyRecents @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("jf_recent_collections")

    val entries: Flow<List<RecentCollection>> = dataStore.data.map { parse(it[key].orEmpty()) }.distinctUntilChanged()

    suspend fun record(id: String, type: String, name: String, subtitle: String?) {
        dataStore.edit { p ->
            val all = parse(p[key].orEmpty()).toMutableList()
            val i = all.indexOfFirst { it.id == id }
            val now = System.currentTimeMillis()
            if (i >= 0) all[i] = all[i].copy(name = name, subtitle = subtitle, count = all[i].count + 1, last = now)
            else all += RecentCollection(id, type, name, subtitle, 1, now)
            p[key] = encode(all.sortedByDescending { it.last }.take(40))
        }
    }

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ')

    private fun encode(list: List<RecentCollection>) = list.joinToString("\n") {
        listOf(it.id, it.type, clean(it.name), clean(it.subtitle.orEmpty()), it.count, it.last).joinToString(SEP.toString())
    }

    private fun parse(raw: String): List<RecentCollection> = raw.lineSequence().mapNotNull { line ->
        val f = line.split(SEP)
        if (f.size < 6) return@mapNotNull null
        RecentCollection(
            id = f[0], type = f[1], name = f[2], subtitle = f[3].ifBlank { null },
            count = f[4].toIntOrNull() ?: 1, last = f[5].toLongOrNull() ?: 0L,
        )
    }.toList()

    private companion object {
        const val SEP = '\u001f'
    }
}
