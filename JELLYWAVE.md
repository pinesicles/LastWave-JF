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
- Step 21: header slimmed to Search, (Feed only) identify-music, profile avatar. Refresh is pull-down (JellyPullRefresh), Downloads icon removed (offline is automatic), no identify button in Library. Upstream merged at Nov 15 (nav collapse, recognition).
- Step 22: Auto quality chosen at play time (PlaybackTrackHook + JellyPlayTimeQuality; hooks in MusicPlayer.playQueue/playNext/addToQueue). Pull-to-refresh tied to real loading (LibraryViewModel.refreshing, feed state.loading). Playlist hero = blurred-cover backdrop; heart moved into top bar between search and menu for albums/playlists. Canvases confirmed working.
- Step 23: Feed top = tile grid (Liked songs + most played 2 + recent, 2/4/6 tiles; falls back to recently added). Plays are recorded locally in JellyRecents (album/playlist plays only). Shuffle all moved to Library half of Feed (ShuffleAllBar). Feed/Library section chips fill width (JellyChipsBar fill). Artist-suggestion shelves moved to the bottom, above YouTube.
- Step 24: playlist page like OG: cover as backdrop behind title + action row (HeroBackdrop), fading into the cover-colour page gradient behind the songs; back button and search/heart/menu are translucent pills overlaid on the cover (TopBar local composable, overCover). Playlists without a cover keep the centred layout.
- Step 25: swipe right on a Jellyfin-list song row = play next IN ORDER (MusicPlayer.playNextInOrder, afterQueued offset on playNext; long-press Play next unchanged). Space above the Feed tiles.
- Step 26: playlist hero closer to OG: full square cover (not cropped) fading at its bottom; bar becomes a cover-colour fade with cover+name+locate once scrolled past the hero; smaller pills (42dp); status bar area is part of the page (cover/gradient) instead of a solid strip; download button same colour as shuffle/locate; small server-loading spinner beside the pill (TracksState.refreshing for saved-copy-while-loading).
- Step 27: swipe-to-play-next fixed: custom drag fires once on release (was repeating while held); ordering counts swipe-added songs from the real ExoPlayer queue using internalTrackId (was matching on stream URL, so last swipe played first).
- Step 28: swipe play-next order now also works under shuffle (placeInsertedIndexInShuffleOrder afterQueued, run counted in play order); active player mode buttons (shuffle etc.) pick white/black by contrast when the artwork-tinted pair is dark-on-dark.
- Step 29: hero polish round 2 — status-bar scrim, scrolled-bar fade covers icons, back button 12dp inset, cover fades into own colour then page colour.
- Step 30: DroppedNeedle request server (data/requests, ui/requests, settings section). 'Request on server' in Feed search + outside rows via LocalRequestAlbum.
- Step 31: request actions in hold menus (YouTube shelf, search rows, track menu), YouTube artist/album search shelves, shuffle active colour over artwork, fixed two-colour cover gradient on playlist hero page.
- Step 32: data monitor (DataMeter network interceptor + canvas listener, per-purpose Wi-Fi/mobile totals) and data saver (128 kbps cap, no canvas/music video, no YouTube shelf). Settings: 'Data' section.
- Step 33a: stop un-routing the USB DAC between tracks (srcRate=null) - fixes clipped intro on auto transitions.
- Step 33: ListenBrainz scrobbling (data/listenbrainz, settings section, hook in ScrobbleRepository); YouTube hold menus as bottom sheets (OutsideActionSheet) + nicer search cards; See on YouTube (youtubeOnly nav flag); Find on server (LocalFindOnServer) in sheets, rows and track menu.
- Step 34: import 'Request missing': not-found songs grouped by album, requested on the request server (ImportRequestMissing.kt, RequestsViewModel.requestBestMatch).
- Step 35: Discover shelves in the Feed from DroppedNeedle (DnShelf, DnAuth cover auth, DiscoverShelves.kt).
- Step 36: RequestWatcher (rescan Jellyfin on import, auto-add requested albums to the playlist they were requested from; RequestContext set by the playlist page).
- Step 37: Library search shows outside sources; per-playlist shuffle (PlaylistShuffleMemory); keep screen on in Now Playing (NowPlayingAwake).
- Step 38: Jellyfin favourites seed the From YouTube shelf (extraSeeds in fetchRecommendations).
- Step 39: Android Auto lyrics (CarLyrics.kt): current synced line shown in artist/album metadata while Auto is connected; setting in 'Android Auto' section.
- Step 40: ListenBrainz recommendations shelf in the Feed (ListenBrainzShelf.kt, ListenBrainzClient.recommendations).
- Step 41: LikeSync (hearts -> ListenBrainz love + DroppedNeedle star), 'Likes sync' settings section, liked songs not on server -> RequestMissingDialog (YouTube likes + Spotify via request server).
- Step 42: missing-liked-songs list is saved (LikeSync.missing), requested albums drop out, Re-check button.
- Step 43: two-way likes sync (LikeMirror: Jellyfin/Spotify/ListenBrainz/app likes, per-service snapshots, removals toggle, preview before apply, auto-sync). Spotify via sp_dc token + api.spotify.com (SpotifyLibrary).
- Step 44: sync issues icon (badge) in Feed/Library header; failed like-sync matches listed with Choose match / Request / Ignore (SyncIssues.kt, LikeMirror.failures).
- Step 45: sync preview rows open a list of what is added and from where; Spotify liked read raised from 1000 to 10000.
- Step 46: likeKey (title base + first artist) fixes false 'missing' matches; one-way sync modes (source -> targets, optional remove extras); preview shows raw vs unique counts.
- Step 47: copy Jellyfin playlists to DroppedNeedle (PlaylistSync, PlaylistSyncDialog); additive only, in 'Likes sync' section.
- Step 48: likes sync runs as WorkManager background work with a foreground notification (LikeSyncWorker); apply no longer tied to the Settings screen.
- Step 49: findSong (title then artist, scored) used by sync/find-on-server; Liked songs downloadable (id liked-songs); sticker-collage hero with heart on Liked songs (LikedCollage.kt).
- Step 50: import keeps unmatched songs per playlist (menu: Unmatched songs → Search library again / Request); import matcher now falls back to title-then-artist findSong.
- Step 51: saved playlist syncs (Jellyfin hub ↔ DroppedNeedle one/both ways, Spotify read-in, removals rule, 6-hourly worker, Sync now).
- Step 52: artist-scoped song matching, full-hero sticker collage with cover-matched colours, 12h cache for Feed YouTube recommendations.
- Step 53: Sync issues moved into the Liked songs ⋮ menu with 'Search library again'; draggable fast-scroll bar on playlist/album/liked lists.
- Step 54: Jellyfin match picker pre-fills title only (Jellyfin search ignores artist names).
- Step 55: sound-alike matching across scripts (Cyrillic vs romanised) via whole-library index fallback in findSong.
- Step 56: scrollbar inset below header and above mini player.
- Step 57: playlist import creates then adds in batches (fixes empty playlists); Last.fm 'Because you listen to' shelf; rearrange/hide Feed shelves.
- Step 58: keep-awake also sets the window flag and only while playing.
- Step 58: 'Turn Canvas on/off' row in the Now Playing track menu (same setting as Settings).
- Step 59: YouTube Music background polling stretched from 1 min to 20 min and paused in data saver (was re-downloading the whole liked list every minute).
