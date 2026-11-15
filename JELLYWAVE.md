# JellyWave notes (read this first, instead of re-exploring the repo)

Personal Jellyfin music client forked from LastWave-native. Android, Kotlin, Compose, Hilt.
Offline-first (weak 2.4 GHz Wi-Fi). Server is Jellyfin 12.4.

## Decisions
- Sources: Jellyfin + local files. Favourites live in Jellyfin (hearts). Keep Last.fm, lyrics (embedded -> Jellyfin -> LRCLIB, cached offline), Android Auto, Cast, widgets, Discord. Audio engine stays as is.
- UI must look like original LastWave: reuse its components, never hand-roll lookalikes (ExpressiveHeader, GlassHeaderLayout, groupShape cards, PlayingWaveBars, ArtworkImage, HeaderActionIcon).
- Keep upstream mergeable: new code in new files, tiny hooks in original files, HIDE YouTube/provider code instead of deleting it. Canvas code is left untouched, not maintained.
- Downloads (built, step 8): album/playlist only, no single tracks, auto-heart on download from Library tab, kept out of Local files tab, one physical file per Jellyfin track under the app's own storage (Android/data/<pkg>/files/Music/JellyWave/<AlbumArtist>/<Album>/; not MediaStore, so never in Local files; lost on uninstall), Room tables (tracks, collections, join) ref-count so album+playlist never duplicate; delete file only when unreferenced. Room v16 (jf_tracks, jf_collections, jf_collection_tracks), WorkManager worker with foreground notification, resume via Range. Downloaded playlists are "kept in sync" (additions auto-download, removals drop unreferenced files).
- Import (step 9): CSV/JSON file or Spotify (reuses the Settings Spotify login: own playlists, Liked Songs, pasted links via data/playlist/SpotifyPlaylistImporter, unofficial web endpoints so may break) / YouTube link (own API key; Apple/Tidal via CSV). Matched to library by title/artist score, creates a Jellyfin playlist, unmatched list has suggestions, manual search and CSV export. Header "+" menu in Library tab.
- Lyrics (step 9): JellyLyrics resolves synced-first: sidecar .lrc > cache > embedded tags > Jellyfin /Audio/{id}/Lyrics > LRCLIB; sidecar saved next to downloads. Hooked in PlayerViewModel.
- Download indicators: JellyDownloads.trackStates -> TrackDownloadIndicator in JellyTrackRow (ProvideTrackDownloads).
- Playlists (Jellyfin): create, add, remove, reorder (move up/down), rename, delete. Done in Batch 2.

## Roadmap
1 look/cleanup (done) - 2 playlists (done) - 3 downloads + auto-download + save/download buttons + Stats-style long-press tray (step 8, untested) - 4 playlist import + offline lyrics + download indicators + Wi-Fi-only (step 9, untested) - 5 settings (step 10, untested: Jellyfin section at top of Settings = account, streaming quality, Wi-Fi-only, delete downloads, sign out; ui/settings/JellyfinSettingsSection.kt) - 6 feed rework (step 12, untested: ui/feed/JellyFeedShelves.kt = time-of-day + mood shelves by library genre keywords, "Because you listened to" via Last.fm recents -> Jellyfin Similar, fallback Jellyfin favourites) - 7 quality switch, lyrics offline, playlist import (CSV/Spotify), cleanup by hiding.

- Long-press menu (step 13, untested): albums/playlists/artists open the same tray as tracks (ui/library/CollectionActions.kt): play, shuffle, queue, save, download/remove, pin to top (data/jellyfin/JellyPins.kt; pinned sort first in Playlists/Albums/Artists/Downloaded tabs), add to playlist (not on Feed shelves), go to artist.
- Album/playlist page (step 14, untested): OG-style action row (shuffle, play, locate) with download status on the right (CollectionDownloadStatus: "done / total" text + % ring, tick when complete, text hides); song count + length under the cover. Still my DetailPage, not the OG Room-based PlaylistDetailScreen (tied to local SavedPlaylist; full OG reuse would need an adapter).
- Cover colours (step 15, untested): ui/library/CoverColors.kt (Palette -> theme-aware tint + text colours, LocalCoverColors). Detail page background gradient from the cover; playlists/liked = hero header (big centred cover, big title), albums = compact side-by-side header. Artists get the tint too.
- Step 16 (untested): in-list search (icon beside the menu, bar grows leftwards; filters songs in playlist/album/liked keeping real indices); Auto quality (JellyAutoQuality: original unless metered or Bluetooth-only -> 320 kbps HLS AAC; toggle in Settings, off by default); Download quality (original default, or MP3 transcode 320/192/128 via /Audio/{id}/stream.mp3 for new downloads; Room v17 adds jf_tracks.transcodeKbps).
- Step 17 DONE: saved playlists open instantly from the local copy; JellyConnectivity drives an offline Feed shelf and Library default tab; global add-to-playlist (JellyAddToPlaylistHost in PlayerHost); JellyFavourites makes the Now Playing heart a Jellyfin favourite; go-to artist/album tries Jellyfin first (JellyNavigator); YouTube download row hidden for jf tracks.
- NEXT: (a) playlist covers reset in the app but stay in Jellyfin (investigate); (b) "Request album" feature: from a track/link/album (and YouTube recommendations) request it on the server (needs Jellyseerr/Lidarr-style API, decide); (c) audit Settings: hide or group non-Jellyfin items, maybe a one-app merge of LastWave + Jellyfin.
- DONE-IDEA (user idea): auto streaming quality = pick FLAC/lossless when the audio output can handle it (Jellyfin device profile / codec checks), else best fitting lossy; toggle in Settings; separate download quality setting; defaults = original/highest.

## Key files (app/src/main/java/com/lastwave/app/)
- data/jellyfin/: JellyfinApi (REST, incl. playlist edit), JellyfinRepository (PlayableTrack mapping, search, favourites), JellyfinSessionStore, JellyfinDiscovery, JellyfinModels.
- data/jellyfin/importer/ + ui/library/JellyImport*.kt: import parsers/matcher/link import and UI; data/jellyfin/JellyLyrics.kt.
- ui/library/: LibraryViewModel (lists, detail stack, search, playlist edits), LibraryScreen (page, grids, detail, dialogs), JellyUi.kt (JellyTrackRow, JellyChipsBar, search field/results, LocalTopInset).
- data/jellyfin/download/: JellyDownloadDb.kt (entities, DAO, migration 15->16), JellyDownloads.kt (storage layout, queue, sync, worker).
- ui/common/TrackMiniTraySheet.kt: original long-press tray; `customRows` hook used by JellyTrackRow.
- ui/feed/JellyfinFeedHome.kt + JellyfinFeedViewModel.kt: Feed tab (Feed | Library chips, search = server).
- ui/shell/MainShell.kt: tab pager; FEED -> JellyfinFeedHome, PLAYLISTS -> LibraryScreen (Library, search = saved items).
- ui/common/: ExpressiveHeader, GlassHeaderLayout, ExpressiveGroup (groupShape), ProfileAvatarViewModel (Last.fm avatar, same source as Stats page).
- ui/playlist/PlaylistScreen.kt: original local playlists, shown in the "On this phone" tab (showHeader = false hook).
- Original (reuse, don't copy): ui/settings/DownloadsCards.kt (row look), ui/feed/FeedTiles.kt (PinnedChipsBar), ui/common/TrackContextMenuSheet.kt.
- Player plays any PlayableTrack with playbackUrl (internalTrackId "jf:<itemId>"); playback/MusicPlayer.kt chromeState.current = now playing.
- Room v15 (data/local/db/AppDatabase.kt); local scan data/localmusic/LocalMusicLibrary.kt (must exclude Music/JellyWave).

## Working rules (save tokens)
- One session per batch; start here; read only the files the batch touches (grep ranges, not whole files).
- User builds in Android Studio and sends only the first build error.
- Git: add LastWave-native as `upstream`; `git fetch upstream && git merge upstream/main`; resolve conflicts in the few touched originals.
- Step 18 DONE: Settings root = Jellyfin, Scrobbling & Spotify, Playback & UI, System & About, then collapsed "Other sources" (YouTube, Modules, Jam). YouTube streaming/download quality tiers hidden via JellySettingsFlags.SHOW_YOUTUBE_QUALITY_TIERS.
- NEXT (step 19): outside sources (YouTube) in Feed shelf + Search section, play + local playlists; request client later (service TBD, Spotify remote dropped).
- Step 19 DONE: outside sources. OutsideSources.kt: Feed search "Outside your library" section (tap to search YouTube), Feed "From YouTube" shelf (OG My Recommendations, online only). Outside songs play, add to LOCAL playlists only (Jellyfin playlists hold server songs only). Source badge. JellyTrackRow isPlaying now falls back to videoId.
- NEXT: request client (service TBD); pending requests auto-add to Jellyfin playlists via ImportMatcher.
