package com.lastwave.app.data.jellyfin

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Test

class JellyfinApiTest {
    private val api = JellyfinApi(OkHttpClient())
    private val repo = JellyfinRepository(api, mockk(relaxed = true))
    private val session = JellyfinSession(
        serverUrl = "http://192.168.1.5:8096",
        token = "tok123",
        userId = "user1",
        deviceId = "dev1",
    )

    @Test
    fun normalizeAddsSchemeAndTrimsSlash() {
        assertThat(JellyfinApi.normalizeServerUrl(" 192.168.1.5:8096/ ")).isEqualTo("http://192.168.1.5:8096")
        assertThat(JellyfinApi.normalizeServerUrl("https://jf.example.com/")).isEqualTo("https://jf.example.com")
        assertThat(JellyfinApi.normalizeServerUrl("   ")).isEmpty()
    }

    @Test
    fun parsesItemsWithNullsAndUnknownFields() {
        val body = """
            {"Items":[{"Id":"a1","Name":"Song","Type":"Audio","Artists":null,"ImageTags":null,
              "RunTimeTicks":1800000000,"SomethingNew":{"x":1},
              "UserData":{"IsFavorite":true,"PlayCount":3},
              "MediaSources":[{"Container":"flac","Bitrate":950000,"MediaStreams":[]}]}],
             "TotalRecordCount":42,"StartIndex":0}
        """.trimIndent()
        val parsed = JellyfinJson.decodeFromString(JfItemsResult.serializer(), body)
        val item = parsed.items.single()
        assertThat(parsed.totalRecordCount).isEqualTo(42)
        assertThat(item.artists).isEmpty()
        assertThat(item.durationMs).isEqualTo(180_000L)
        assertThat(item.isFavorite).isTrue()
        assertThat(item.mediaSources.single().bitrate).isEqualTo(950_000L)
    }

    @Test
    fun originalStreamIsStaticAndCarriesToken() {
        val url = api.originalUrl(session, "a1")
        assertThat(url).startsWith("http://192.168.1.5:8096/Audio/a1/stream?")
        assertThat(url).contains("static=true")
        assertThat(url).contains("api_key=tok123")
    }

    @Test
    fun streamTargetUsesOriginalWhenUncappedOrAlreadySmall() {
        assertThat(api.streamTarget(session, "a1", maxKbps = 0, sourceKbps = 950).transcoded).isFalse()
        assertThat(api.streamTarget(session, "a1", maxKbps = 320, sourceKbps = 256).transcoded).isFalse()
    }

    @Test
    fun streamTargetTranscodesToHlsWhenOverCapOrUnknown() {
        val big = api.streamTarget(session, "a1", maxKbps = 320, sourceKbps = 950)
        assertThat(big.transcoded).isTrue()
        assertThat(big.mimeType).isEqualTo(JellyfinApi.HLS_MIME)
        assertThat(big.url).contains("/Audio/a1/universal")
        assertThat(big.url).contains("maxStreamingBitrate=320000")
        // Unknown source bitrate + a cap: play it safe and transcode.
        assertThat(api.streamTarget(session, "a1", maxKbps = 320, sourceKbps = null).transcoded).isTrue()
    }

    @Test
    fun mapsTrackToPlayableTrack() {
        val item = JfItem(
            id = "a1", name = "Song", album = "Album", albumId = "alb9",
            artists = listOf("A", "B"), trackNumber = 3, runTimeTicks = 1_800_000_000L,
            albumPrimaryImageTag = "tagX",
        )
        val track = repo.toPlayable(session, item, maxKbps = 0)
        assertThat(track.title).isEqualTo("Song")
        assertThat(track.artist).isEqualTo("A, B")
        assertThat(track.internalTrackId).isEqualTo("jf:a1")
        assertThat(track.videoId).isNull()
        assertThat(track.durationMs).isEqualTo(180_000L)
        assertThat(track.artworkUrl).contains("/Items/alb9/Images/Primary")
        assertThat(track.jellyfinItemIdOrNull()).isEqualTo("a1")
    }
}
