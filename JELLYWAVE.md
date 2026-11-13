# JellyWave notes (read this first, instead of re-exploring the repo)

Personal Jellyfin music client forked from LastWave-native. Android, Kotlin, Compose, Hilt.
Offline-first (weak 2.4 GHz Wi-Fi). Server is Jellyfin 12.4.

## Decisions
- Sources: Jellyfin + local files. Favourites live in Jellyfin (hearts). Keep Last.fm, lyrics (embedded -> Jellyfin -> LRCLIB, cached offline), Android Auto, Cast, widgets, Discord. Audio engine stays as is.
- UI must look like original LastWave: reuse its components, never hand-roll lookalikes (ExpressiveHeader, GlassHeaderLayout, groupShape cards, PlayingWaveBars, ArtworkImage, HeaderActionIcon).
- Keep upstream mergeable: new code in new files, tiny hooks in original files, HIDE YouTube/provider code instead of deleting it. Canvas code is left untouched, not maintained.
- Downloads (not built yet): album/playlist only, no single tracks, auto-heart on download from Library tab, kept out of Local files tab, one physical file per Jellyfin track under Music/JellyWave/<AlbumArtist>/<Album>/, Room tables (tracks, collections, join) ref-count so album+playlist never duplicate; delete file only when unreferenced. Needs Room 15->16 migration (no destructive fallback), WorkManager, resume via Range. Playlists with "keep downloaded" auto-download additions.
- Playlists (Jellyfin): create, add, remove, reorder (move up/down), rename, delete. Done in Batch 2.

## Roadmap
1 look/cleanup (done) - 2 playlists (done) - 3 downloads core - 4 playlist auto-download - 5 settings (Jellyfin account, quality, Last.fm) - 6 feed rework (Last.fm "because you listened", mood/time) - 7 quality switch, lyrics offline, playlist import (CSV/Spotify), cleanup by hiding.

## Key files (app/src/main/java/com/lastwave/app/)
- data/jellyfin/: JellyfinApi (REST, incl. playlist edit), JellyfinRepository (PlayableTrack mapping, search, favourites), JellyfinSessionStore, JellyfinDiscovery, JellyfinModels.
- ui/library/: LibraryViewModel (lists, detail stack, search, playlist edits), LibraryScreen (page, grids, detail, dialogs), JellyUi.kt (JellyTrackRow, JellyChipsBar, search field/results, LocalTopInset).
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
