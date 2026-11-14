# JellyWave notes (read this first, instead of re-exploring the repo)

Personal Jellyfin music client forked from LastWave-native. Android, Kotlin, Compose, Hilt.
Offline-first (weak 2.4 GHz Wi-Fi). Server is Jellyfin 12.4.

## Decisions
- Sources: Jellyfin + local files. Favourites live in Jellyfin (hearts). Keep Last.fm, lyrics (embedded -> Jellyfin -> LRCLIB, cached offline), Android Auto, Cast, widgets, Discord. Audio engine stays as is.
- UI must look like original LastWave: reuse its components, never hand-roll lookalikes (ExpressiveHeader, GlassHeaderLayout, groupShape cards, PlayingWaveBars, ArtworkImage, HeaderActionIcon).
- Keep upstream mergeable: new code in new files, tiny hooks in original files, HIDE YouTube/provider code instead of deleting it. Canvas code is left untouched, not maintained.
- Downloads (built, step 8): album/playlist only, no single tracks, auto-heart on download from Library tab, kept out of Local files tab, one physical file per Jellyfin track under the app's own storage (Android/data/<pkg>/files/Music/JellyWave/<AlbumArtist>/<Album>/; not MediaStore, so never in Local files; lost on uninstall), Room tables (tracks, collections, join) ref-count so album+playlist never duplicate; delete file only when unreferenced. Room v16 (jf_tracks, jf_collections, jf_collection_tracks), WorkManager worker with foreground notification, resume via Range. Downloaded playlists are "kept in sync" (additions auto-download, removals drop unreferenced files).
- Playlists (Jellyfin): create, add, remove, reorder (move up/down), rename, delete. Done in Batch 2.

## Roadmap
1 look/cleanup (done) - 2 playlists (done) - 3 downloads + auto-download + save/download buttons + Stats-style long-press tray (step 8, untested) - 4 playlist import (CSV/JSON first; YouTube needs API key; Apple/Tidal via CSV) - 5 settings (Jellyfin account, quality, Last.fm) - 6 feed rework (Last.fm "because you listened", mood/time) - 7 quality switch, lyrics offline, playlist import (CSV/Spotify), cleanup by hiding.

## Key files (app/src/main/java/com/lastwave/app/)
- data/jellyfin/: JellyfinApi (REST, incl. playlist edit), JellyfinRepository (PlayableTrack mapping, search, favourites), JellyfinSessionStore, JellyfinDiscovery, JellyfinModels.
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
