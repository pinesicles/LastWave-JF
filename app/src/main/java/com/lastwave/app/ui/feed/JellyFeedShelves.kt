package com.lastwave.app.ui.feed

import com.lastwave.app.data.jellyfin.JellyfinRepository
import com.lastwave.app.data.jellyfin.JfItem
import com.lastwave.app.data.repository.HomeRepository
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** A horizontal row on the feed. [order] places it: below 50 sits above "Recently added", the rest below it. */
data class FeedShelf(
    val key: String,
    val title: String,
    val items: List<JfItem>,
    val artists: Boolean = false,
    val order: Int,
)

/**
 * Builds the personalised shelves: time of day, moods (picked by genre from your own library) and
 * "Because you listened to…" (seeded from Last.fm, or your Jellyfin favourites without Last.fm).
 * Each builder is independent so the feed can show shelves as they arrive.
 */
@Singleton
class JellyFeedShelves @Inject constructor(
    private val repository: JellyfinRepository,
    private val home: HomeRepository,
) {
    private class Mood(val key: String, val title: String, val keywords: List<String>)

    private val moods = listOf(
        Mood("mood-energy", "Energetic", listOf("rock", "metal", "punk", "dance", "electro", "hip hop", "hip-hop", "drum", "house", "techno")),
        Mood("mood-chill", "Chill", listOf("chill", "ambient", "downtempo", "lo-fi", "lofi", "acoustic", "jazz", "trip hop", "bossa")),
        Mood("mood-happy", "Feel good", listOf("pop", "funk", "disco", "reggae", "ska", "soul", "motown")),
        Mood("mood-blue", "In your feelings", listOf("blues", "slowcore", "shoegaze", "emo", "singer", "dream pop", "sad", "folk")),
        Mood("mood-focus", "Focus", listOf("classical", "instrumental", "post-rock", "soundtrack", "piano", "score", "ambient")),
    )

    private fun period(hour: Int): Mood = when (hour) {
        in 5..10 -> Mood("time", "Good morning", listOf("acoustic", "folk", "indie", "pop", "singer", "soul"))
        in 11..16 -> Mood("time", "Afternoon focus", listOf("electronic", "ambient", "instrumental", "classical", "jazz", "post-rock"))
        in 17..21 -> Mood("time", "Evening vibes", listOf("rock", "pop", "hip hop", "hip-hop", "r&b", "funk", "dance"))
        else -> Mood("time", "Late night", listOf("ambient", "chill", "downtempo", "lo-fi", "lofi", "jazz", "trip hop", "dream pop"))
    }

    private suspend fun byGenres(mood: Mood, genres: List<String>, order: Int): FeedShelf? {
        val matched = genres.filter { g -> mood.keywords.any { g.contains(it, ignoreCase = true) } }.take(8)
        if (matched.isEmpty()) return null
        val albums = repository.albumsByGenres(matched, 20)
        return albums.takeIf { it.size >= 3 }?.let { FeedShelf(mood.key, mood.title, it, order = order) }
    }

    suspend fun timeOfDay(genres: List<String>): FeedShelf? = byGenres(period(LocalTime.now().hour), genres, 10)

    suspend fun moods(genres: List<String>): List<FeedShelf> =
        moods.mapIndexedNotNull { i, m -> runCatching { byGenres(m, genres, 60 + i) }.getOrNull() }

    /** Up to three "Because you…" shelves. */
    suspend fun because(): List<FeedShelf> {
        val shelves = mutableListOf<FeedShelf>()
        val recent = runCatching { home.fetchRecentTracks(limit = 50).getOrNull()?.tracks }.getOrNull().orEmpty()

        var artistsDone = 0
        for (name in recent.map { it.artist.displayName }.filter { it.isNotBlank() }.distinct()) {
            if (artistsDone == 2) break
            val artist = runCatching { repository.findArtist(name) }.getOrNull() ?: continue
            val similar = runCatching { repository.similarArtists(artist.id) }.getOrNull().orEmpty()
            if (similar.isEmpty()) continue
            shelves += FeedShelf("because-a-${artist.id}", "Because you listened to ${artist.name}", similar, artists = true, order = 20 + artistsDone)
            artistsDone++
        }
        for (t in recent) {
            val album = t.album.displayName.takeIf { it.isNotBlank() } ?: continue
            val found = runCatching { repository.findAlbum(album, t.artist.displayName) }.getOrNull() ?: continue
            val similar = runCatching { repository.similarAlbums(found.id) }.getOrNull().orEmpty().filter { it.id != found.id }
            if (similar.isNotEmpty()) {
                shelves += FeedShelf("because-b-${found.id}", "Because you played ${found.name}", similar, order = 25)
                break
            }
        }
        if (shelves.isEmpty()) {
            // No Last.fm history: seed from what you've hearted on Jellyfin.
            val fav = runCatching { repository.favoriteArtists() }.getOrNull().orEmpty().shuffled().take(2)
            fav.forEachIndexed { i, artist ->
                val similar = runCatching { repository.similarArtists(artist.id) }.getOrNull().orEmpty()
                if (similar.isNotEmpty()) shelves += FeedShelf("because-f-${artist.id}", "Because you liked ${artist.name}", similar, artists = true, order = 20 + i)
            }
        }
        return shelves
    }

    suspend fun genres(): List<String> = repository.genreNames()
}
