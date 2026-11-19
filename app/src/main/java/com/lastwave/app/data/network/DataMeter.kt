@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.data.network

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What a chunk of network traffic was for. */
enum class DataCategory(val label: String) {
    STREAMING("Streaming music"),
    DOWNLOADS("Downloads"),
    COVERS("Covers & images"),
    CANVAS("Canvas & video"),
    SERVER("Library & browsing"),
    YOUTUBE("YouTube search & feeds"),
    LYRICS("Lyrics"),
    SCROBBLING("Last.fm & scrobbling"),
    REQUESTS("Request server"),
    OTHER("Other"),
}

/**
 * Counts the app's network bytes by purpose, split by Wi-Fi vs mobile data, and keeps daily totals.
 * Everything that goes through the shared OkHttp client (including the main player and image loading)
 * is counted by [interceptor]; the canvas players use their own HTTP stack and report through [listener].
 * Totals are kept for about a month in a private SharedPreferences file.
 */
object DataMeter {
    private const val PREFS = "jw_data_usage"
    private const val MODE_KEY = "saver_mode"
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile private var prefs: SharedPreferences? = null
    private val pending = ConcurrentHashMap<String, AtomicLong>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while the default network is mobile data. */
    val mobile = MutableStateFlow(false)

    /** Data saver: 0 off, 1 on mobile data only, 2 always. */
    val mode = MutableStateFlow(0)

    /** Whether the data saver is in effect right now. */
    val saving: StateFlow<Boolean> = combine(mode, mobile) { m, mob -> m == 2 || (m == 1 && mob) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun savingNow(): Boolean = saving.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mode.value = p.getInt(MODE_KEY, 0)
        prune(p)
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    mobile.value = !wifi && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                }
            })
        }
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "data-meter").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ flush() }, 10, 10, TimeUnit.SECONDS)
    }

    fun setMode(value: Int) {
        mode.value = value
        prefs?.edit()?.putInt(MODE_KEY, value)?.apply()
    }

    fun add(category: DataCategory, bytes: Long) {
        if (bytes <= 0) return
        val key = "${dayFormat.format(Date())}|${category.name}|${if (mobile.value) "m" else "w"}"
        pending.getOrPut(key) { AtomicLong() }.addAndGet(bytes)
    }

    @Synchronized
    private fun flush() {
        val p = prefs ?: return
        val edit = p.edit()
        var any = false
        pending.forEach { (key, counter) ->
            val delta = counter.getAndSet(0)
            if (delta > 0) { edit.putLong(key, p.getLong(key, 0L) + delta); any = true }
        }
        if (any) edit.apply()
    }

    private fun prune(p: SharedPreferences) {
        val cutoff = dayFormat.format(Date(System.currentTimeMillis() - 31L * 24 * 3600 * 1000))
        val edit = p.edit()
        p.all.keys.filter { it.contains('|') && it.substringBefore('|') < cutoff }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** Wi-Fi and mobile bytes per category for the last [days] days (1 = today). */
    @Synchronized
    fun snapshot(days: Int): Map<DataCategory, Pair<Long, Long>> {
        flush()
        val p = prefs ?: return emptyMap()
        val first = dayFormat.format(Date(System.currentTimeMillis() - (days - 1) * 24L * 3600 * 1000))
        val wifi = HashMap<DataCategory, Long>()
        val cell = HashMap<DataCategory, Long>()
        p.all.forEach { (key, value) ->
            val parts = key.split('|')
            if (parts.size != 3 || value !is Long || parts[0] < first) return@forEach
            val cat = runCatching { DataCategory.valueOf(parts[1]) }.getOrNull() ?: return@forEach
            if (parts[2] == "m") cell[cat] = (cell[cat] ?: 0L) + value else wifi[cat] = (wifi[cat] ?: 0L) + value
        }
        return DataCategory.values().associateWith { (wifi[it] ?: 0L) to (cell[it] ?: 0L) }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        prefs?.edit()?.apply { prefs?.all?.keys?.filter { it.contains('|') }?.forEach { remove(it) } }?.apply()
    }

    private val jellyPrefixes = listOf(
        "/Items", "/Users", "/Playlists", "/Artists", "/Albums", "/Genres", "/MusicGenres",
        "/UserFavoriteItems", "/UserPlayedItems", "/System", "/Sessions", "/Playing", "/Branding",
    )

    private fun classify(request: okhttp3.Request, contentType: String?): DataCategory {
        request.tag(DataCategory::class.java)?.let { return it }
        val host = request.url.host.lowercase()
        val path = request.url.encodedPath
        return when {
            host.endsWith("lrclib.net") -> DataCategory.LYRICS
            host.endsWith("audioscrobbler.com") || host.endsWith("listenbrainz.org") -> DataCategory.SCROBBLING
            host.endsWith("googlevideo.com") -> DataCategory.STREAMING
            contentType?.startsWith("image/") == true || path.contains("/Images/") -> DataCategory.COVERS
            path.contains("/Audio/") || path.contains("/Videos/") ||
                contentType?.startsWith("audio/") == true || contentType?.startsWith("video/") == true ||
                contentType?.contains("mpegurl", ignoreCase = true) == true -> DataCategory.STREAMING
            host.contains("youtube.com") || host.contains("youtubei") || host.contains("ytimg.com") -> DataCategory.YOUTUBE
            path.startsWith("/api/v3/") -> DataCategory.REQUESTS
            jellyPrefixes.any { path.startsWith(it) } -> DataCategory.SERVER
            else -> DataCategory.OTHER
        }
    }

    /** Add to OkHttpClient with `addNetworkInterceptor` so only real network bytes (not cache hits) are counted. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body ?: return@Interceptor response
        val category = classify(request, response.header("Content-Type"))
        request.body?.contentLength()?.takeIf { it > 0 }?.let { add(category, it) }
        val counting = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) add(category, n)
                return n
            }
        }
        response.newBuilder()
            .body(counting.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    /** For ExoPlayer data sources outside the shared OkHttp client (canvas videos). */
    fun listener(category: DataCategory): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) add(category, bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }
}
