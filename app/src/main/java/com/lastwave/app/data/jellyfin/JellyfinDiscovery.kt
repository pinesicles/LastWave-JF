package com.lastwave.app.data.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredServer(val name: String, val address: String, val id: String?)

@Serializable
private data class JfDiscoveryReply(
    @SerialName("Address") val address: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)

/**
 * Finds Jellyfin servers on the current Wi-Fi network.
 *
 * 1. Jellyfin's own discovery: broadcast "Who is JellyfinServer?" over UDP to
 *    port 7359; every server on the network answers with its address.
 * 2. Fallback if nothing answered (some routers block broadcasts between
 *    devices): ask every address in the phone's /24 subnet on port 8096.
 */
@Singleton
class JellyfinDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private companion object {
        const val DISCOVERY_PORT = 7359
        const val DEFAULT_HTTP_PORT = 8096
        const val QUESTION = "Who is JellyfinServer?"
    }

    suspend fun discover(udpTimeoutMs: Int = 2_000): List<DiscoveredServer> {
        val viaUdp = udpDiscover(udpTimeoutMs)
        if (viaUdp.isNotEmpty()) return viaUdp
        return subnetSweep()
    }

    // ---- local network info -------------------------------------------------

    private data class LocalNet(val ip: Inet4Address, val prefix: Int)

    private fun localNet(): LocalNet? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        val addr = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null
        return LocalNet(addr.address as Inet4Address, addr.prefixLength)
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        localNet()?.let { net ->
            runCatching {
                val ip = net.ip.address
                val mask = if (net.prefix <= 0) 0 else (-1 shl (32 - net.prefix))
                val ipInt = ip.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val b = ipInt or mask.inv()
                targets += InetAddress.getByAddress(
                    byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()),
                )
            }
        }
        return targets
    }

    // ---- 1. UDP broadcast -----------------------------------------------------

    private suspend fun udpDiscover(timeoutMs: Int): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredServer>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val question = QUESTION.toByteArray(Charsets.UTF_8)
                broadcastTargets().forEach { target ->
                    runCatching { socket.send(DatagramPacket(question, question.size, target, DISCOVERY_PORT)) }
                }
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                val buffer = ByteArray(2_048)
                while (SystemClock.elapsedRealtime() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val reply = runCatching {
                            JellyfinJson.decodeFromString(JfDiscoveryReply.serializer(), text)
                        }.getOrNull() ?: continue
                        val address = reply.address?.trim()?.trimEnd('/').orEmpty()
                        if (address.isNotEmpty()) {
                            found[address] = DiscoveredServer(
                                name = reply.name?.takeIf { it.isNotBlank() } ?: address,
                                address = address,
                                id = reply.id,
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        // keep listening until the deadline
                    }
                }
            }
        } catch (_: IOException) {
            // No usable network: report nothing found.
        }
        found.values.toList()
    }

    // ---- 2. Subnet sweep --------------------------------------------------------

    private suspend fun subnetSweep(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val net = localNet() ?: return@withContext emptyList()
        val own = net.ip.address
        // Only the phone's own /24 (254 addresses), even on a wider network.
        val base = "${own[0].toInt() and 0xFF}.${own[1].toInt() and 0xFF}.${own[2].toInt() and 0xFF}"
        val ownLast = own[3].toInt() and 0xFF
        val probe = http.newBuilder()
            .connectTimeout(450, TimeUnit.MILLISECONDS)
            .readTimeout(700, TimeUnit.MILLISECONDS)
            .callTimeout(1_200, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val gate = Semaphore(48)
        coroutineScope {
            (1..254).filter { it != ownLast }.map { last ->
                async {
                    gate.withPermit {
                        val address = "http://$base.$last:$DEFAULT_HTTP_PORT"
                        runCatching {
                            probe.newCall(Request.Builder().url("$address/System/Info/Public").get().build())
                                .execute().use { r ->
                                    if (!r.isSuccessful) return@use null
                                    val info = JellyfinJson.decodeFromString(
                                        JfPublicInfo.serializer(),
                                        r.body?.string().orEmpty(),
                                    )
                                    // A real Jellyfin answers with its server id and version.
                                    if (info.id.isNullOrBlank() || info.version.isNullOrBlank()) {
                                        null
                                    } else {
                                        DiscoveredServer(info.serverName ?: address, address, info.id)
                                    }
                                }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
