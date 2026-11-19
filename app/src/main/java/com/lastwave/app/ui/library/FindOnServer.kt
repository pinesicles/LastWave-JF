package com.lastwave.app.ui.library

import androidx.compose.runtime.staticCompositionLocalOf

internal enum class FindKind { SONG, ALBUM, ARTIST }

/** What to look for on the Jellyfin server: [title] is the song or album name (the artist's name for ARTIST). */
internal class FindQuery(val kind: FindKind, val title: String, val artist: String, val album: String? = null)

/** "Find on server": checks whether a YouTube song, album or artist is already in your Jellyfin library, and opens it if so. */
internal val LocalFindOnServer = staticCompositionLocalOf<(FindQuery) -> Unit> { {} }
