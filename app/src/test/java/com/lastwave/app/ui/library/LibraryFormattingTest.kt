package com.lastwave.app.ui.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryFormattingTest {
    @Test
    fun formatsDurations() {
        assertThat(formatDuration(0)).isEqualTo("0:00")
        assertThat(formatDuration(65_000)).isEqualTo("1:05")
        assertThat(formatDuration(3_725_000)).isEqualTo("62:05")
        assertThat(formatDuration(-5)).isEqualTo("0:00")
    }

    @Test
    fun savedLibraryOpensOnPlaylists() {
        val tabs = tabsFor(LibraryMode.SAVED)
        assertThat(tabs.first()).isEqualTo(LibraryTab.PLAYLISTS)
        assertThat(tabs).containsExactly(
            LibraryTab.PLAYLISTS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.PHONE,
        ).inOrder()
    }

    @Test
    fun serverLibraryListsEverything() {
        assertThat(tabsFor(LibraryMode.SERVER))
            .containsExactly(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.SONGS).inOrder()
    }
}
