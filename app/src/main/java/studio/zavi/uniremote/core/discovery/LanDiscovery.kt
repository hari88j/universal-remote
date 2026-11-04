package studio.zavi.uniremote.core.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import studio.zavi.uniremote.core.Brand
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.net.Http
import studio.zavi.uniremote.core.samsung.SamsungController

/**
 * Finds Samsung, LG, Roku and Fire TV devices with SSDP.
 * (Android / Google TV is found with mDNS on the Android side.)
 */
object LanDiscovery {
    const val ST_ROKU = "roku:ecp"
    const val ST_SAMSUNG = "urn:samsung.com:device:RemoteControlReceiver:1"
    const val ST_LG = "urn:lge-com:service:webos-second-screen:1"
    const val ST_DIAL = "urn:dial-multiscreen-org:service:dial:1"

    suspend fun discover(timeoutMs: Long = 3000): List<TvDevice> = withContext(Dispatchers.IO) {
        val responses = Ssdp.search(listOf(ST_ROKU, ST_SAMSUNG, ST_LG, ST_DIAL), timeoutMs)
        // One entry per (ip, search target); a TV can answer several times.
        val unique = responses.distinctBy { it.ip to it.st }
        val found = coroutineScope {
            unique.map { r -> async { runCatching { classify(r) }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        merge(found)
    }

    /** Keeps one entry per brand+IP, preferring the one with the most detail. */
    fun merge(devices: List<TvDevice>): List<TvDevice> =
        devices.groupBy { it.id }.values.map { group ->
            group.maxByOrNull { (if (it.mac != null) 2 else 0) + (if (it.model != null) 1 else 0) }!!
        }

    private fun classify(r: SsdpResponse): TvDevice? {
        val st = r.st ?: return null
        return when {
            st.equals(ST_ROKU, ignoreCase = true) -> roku(r.ip)
            st.equals(ST_SAMSUNG, ignoreCase = true) -> samsung(r.ip)
            st.equals(ST_LG, ignoreCase = true) -> lg(r)
            st.equals(ST_DIAL, ignoreCase = true) -> dial(r)
            else -> null
        }
    }

    private fun roku(ip: String): TvDevice {
        val info = runCatching { Http.get("http://$ip:8060/query/device-info", 2500) }.getOrNull()
        val name = info?.let {
            Xml.tag(it, "user-device-name") ?: Xml.tag(it, "friendly-device-name") ?: Xml.tag(it, "model-name")
        }
        return TvDevice(name = name ?: "Roku", ip = ip, brand = Brand.ROKU, model = info?.let { Xml.tag(it, "model-name") })
    }

    private fun samsung(ip: String): TvDevice {
        val info = SamsungController.fetchInfo(ip)
        return TvDevice(
            name = info?.name ?: "Samsung TV",
            ip = ip,
            brand = Brand.SAMSUNG,
            mac = info?.mac,
            model = info?.model,
        )
    }

    private fun lg(r: SsdpResponse): TvDevice {
        val xml = r.location?.let { runCatching { Http.get(it, 2500) }.getOrNull() }
        val name = xml?.let { Xml.tag(it, "friendlyName") }
        return TvDevice(name = name ?: "LG TV", ip = r.ip, brand = Brand.LG, model = xml?.let { Xml.tag(it, "modelName") })
    }

    private fun dial(r: SsdpResponse): TvDevice? {
        val xml = r.location?.let { runCatching { Http.get(it, 2500) }.getOrNull() } ?: return null
        val manufacturer = Xml.tag(xml, "manufacturer") ?: return null
        if (!manufacturer.contains("Amazon", ignoreCase = true)) return null
        return TvDevice(
            name = Xml.tag(xml, "friendlyName") ?: "Fire TV",
            ip = r.ip,
            brand = Brand.FIRE_TV,
            model = Xml.tag(xml, "modelName"),
        )
    }
}

/** Just enough XML reading for UPnP descriptions and Roku replies. */
object Xml {
    fun tag(xml: String, name: String): String? {
        val match = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return unescape(match.groupValues[1].trim()).takeIf { it.isNotEmpty() }
    }

    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
