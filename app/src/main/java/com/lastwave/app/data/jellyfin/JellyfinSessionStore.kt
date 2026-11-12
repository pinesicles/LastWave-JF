package com.lastwave.app.data.jellyfin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Jellyfin login and stream-quality choice in the app's existing
 * preferences DataStore. Stored in app-private storage; the access token is
 * not additionally encrypted yet.
 */
@Singleton
class JellyfinSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("jf_server_url")
        val TOKEN = stringPreferencesKey("jf_token")
        val USER_ID = stringPreferencesKey("jf_user_id")
        val DEVICE_ID = stringPreferencesKey("jf_device_id")
        val SERVER_NAME = stringPreferencesKey("jf_server_name")
        val SERVER_VERSION = stringPreferencesKey("jf_server_version")

        /** 0 = original file (no transcoding); otherwise a max stream bitrate in kbps. */
        val STREAM_KBPS = intPreferencesKey("jf_stream_kbps")
    }

    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** Null when not logged in. */
    val session: Flow<JellyfinSession?> = data.map { p ->
        val url = p[Keys.SERVER_URL]
        val token = p[Keys.TOKEN]
        val user = p[Keys.USER_ID]
        val device = p[Keys.DEVICE_ID]
        if (url.isNullOrBlank() || token.isNullOrBlank() || user.isNullOrBlank() || device.isNullOrBlank()) {
            null
        } else {
            JellyfinSession(
                serverUrl = url,
                token = token,
                userId = user,
                deviceId = device,
                serverName = p[Keys.SERVER_NAME].orEmpty(),
                serverVersion = p[Keys.SERVER_VERSION].orEmpty(),
            )
        }
    }

    val streamKbps: Flow<Int> = data.map { it[Keys.STREAM_KBPS] ?: 0 }

    suspend fun current(): JellyfinSession? = session.first()

    suspend fun currentStreamKbps(): Int = streamKbps.first()

    suspend fun save(session: JellyfinSession) {
        dataStore.edit { p ->
            p[Keys.SERVER_URL] = session.serverUrl
            p[Keys.TOKEN] = session.token
            p[Keys.USER_ID] = session.userId
            p[Keys.DEVICE_ID] = session.deviceId
            p[Keys.SERVER_NAME] = session.serverName
            p[Keys.SERVER_VERSION] = session.serverVersion
        }
    }

    /** Logs out but keeps the device id, so the server sees the same device on re-login. */
    suspend fun clear() {
        dataStore.edit { p ->
            p.remove(Keys.SERVER_URL)
            p.remove(Keys.TOKEN)
            p.remove(Keys.USER_ID)
            p.remove(Keys.SERVER_NAME)
            p.remove(Keys.SERVER_VERSION)
        }
    }

    suspend fun setStreamKbps(kbps: Int) {
        dataStore.edit { it[Keys.STREAM_KBPS] = kbps.coerceAtLeast(0) }
    }

    /** Stable per-install id; created on first use. */
    suspend fun deviceId(): String {
        data.first()[Keys.DEVICE_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        dataStore.edit { p ->
            if (p[Keys.DEVICE_ID].isNullOrBlank()) p[Keys.DEVICE_ID] = fresh
        }
        return data.first()[Keys.DEVICE_ID] ?: fresh
    }
}
