package studio.zavi.uniremote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.zavi.uniremote.core.Brand
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.discovery.LanDiscovery
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds TVs on the Wi-Fi: mDNS for Android TV / Fire TV, SSDP for Samsung, LG, Roku and Fire TV. */
class DiscoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = NsdDiscovery(appContext)

    suspend fun scan(): List<TvDevice> = withContext(Dispatchers.IO) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("uniremote-scan")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            coroutineScope {
                val ssdp = async { runCatching { LanDiscovery.discover(3500) }.getOrDefault(emptyList()) }
                val mdns = async { runCatching { nsd.discover(listOf(TYPE_ANDROID_TV, TYPE_FIRE_TV), 4500) }.getOrDefault(emptyList()) }
                val fromMdns = mdns.await().map { hit ->
                    if (hit.type == TYPE_ANDROID_TV) {
                        TvDevice(name = hit.name, ip = hit.ip, brand = Brand.ANDROID_TV)
                    } else {
                        val name = hit.attributes["n"]?.takeIf { it.isNotBlank() } ?: "Fire TV"
                        TvDevice(name = name, ip = hit.ip, brand = Brand.FIRE_TV)
                    }
                }
                LanDiscovery.merge(ssdp.await() + fromMdns).sortedBy { it.name.lowercase() }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    companion object {
        const val TYPE_ANDROID_TV = "_androidtvremote2._tcp"
        const val TYPE_FIRE_TV = "_amzn-wplay._tcp"
    }
}

/** Thin coroutine wrapper over Android's NsdManager (mDNS / DNS-SD). */
class NsdDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class Hit(val type: String, val name: String, val ip: String, val port: Int, val attributes: Map<String, String>)

    suspend fun discover(types: List<String>, durationMs: Long): List<Hit> = coroutineScope {
        val found = Channel<Pair<String, NsdServiceInfo>>(Channel.UNLIMITED)
        val listeners = types.mapNotNull { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    found.trySend(type to serviceInfo)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listener
            } catch (e: Exception) {
                null
            }
        }

        val hits = mutableListOf<Hit>()
        val seen = HashSet<String>()
        // Resolve one at a time: older Android versions fail parallel resolves.
        val resolver = launch {
            for ((type, info) in found) {
                if (!seen.add(type + "/" + info.serviceName)) continue
                val resolved = resolve(info) ?: continue
                val ip = ipv4(resolved) ?: continue
                hits += Hit(type, resolved.serviceName, ip, resolved.port, attributes(resolved))
            }
        }

        delay(durationMs)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        found.close()
        if (withTimeoutOrNull(4000) { resolver.join() } == null) resolver.cancelAndJoin()
        hits.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ipv4(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        }
        val host = info.host ?: return null
        return if (host is Inet4Address) host.hostAddress else null
    }

    private fun attributes(info: NsdServiceInfo): Map<String, String> =
        runCatching {
            info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        }.getOrDefault(emptyMap())
}
