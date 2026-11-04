package studio.zavi.uniremote.core.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class SsdpResponse(val ip: String, val headers: Map<String, String>) {
    val st: String? get() = headers["st"]
    val location: String? get() = headers["location"]
    val server: String? get() = headers["server"]
}

/** SSDP (UPnP) M-SEARCH: asks every device on the LAN that matches a search target to answer. */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** Blocking. Sends one M-SEARCH per target (twice, UDP is lossy) and collects replies for [timeoutMs]. */
    fun search(
        targets: List<String>,
        timeoutMs: Long = 3000,
        address: String = MULTICAST_ADDRESS,
        port: Int = PORT,
    ): List<SsdpResponse> {
        val results = mutableListOf<SsdpResponse>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 250
            val dest = InetAddress.getByName(address)
            repeat(2) {
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n" +
                        "\r\n"
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, dest, port)) }
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parse(text, packet.address.hostAddress ?: continue)?.let { results += it }
            }
        }
        return results
    }

    fun parse(text: String, ip: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("HTTP/", ignoreCase = true) && !first.startsWith("NOTIFY", ignoreCase = true)) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        return SsdpResponse(ip, headers)
    }
}
