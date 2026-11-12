package com.lastwave.app.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What we persist after a successful login. */
data class JellyfinSession(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val deviceId: String,
    val serverName: String = "",
    val serverVersion: String = "",
)

class JellyfinException(message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

@Serializable
data class JfPublicInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JfAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable
data class JfAuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: JfUser,
)

@Serializable
data class JfUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class JfItemsResult(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("IndexNumber") val trackNumber: Int? = null,
    @SerialName("ParentIndexNumber") val discNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JfUserData? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
) {
    val durationMs: Long? get() = runTimeTicks?.let { it / 10_000L }?.takeIf { it > 0 }
    val isFavorite: Boolean get() = userData?.isFavorite == true
}

@Serializable
data class JfUserData(
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("PlayCount") val playCount: Int = 0,
)

@Serializable
data class JfMediaSource(
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JfMediaStream> = emptyList(),
)

@Serializable
data class JfMediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("SampleRate") val sampleRate: Int? = null,
)

/** One page of a paged library query. */
data class JfPage(val items: List<JfItem>, val total: Int, val start: Int) {
    val hasMore: Boolean get() = start + items.size < total
}
