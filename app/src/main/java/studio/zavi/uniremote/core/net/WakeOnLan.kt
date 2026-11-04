package studio.zavi.uniremote.core.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends Wake-on-LAN magic packets so a TV in standby can be switched on over Wi-Fi. */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Wakes [macs] by broadcasting on the whole network and on the TV's /24 subnet. Blocking. */
    fun wake(macs: List<String>, deviceIp: String?) {
        val targets = buildList {
            add("255.255.255.255")
            if (deviceIp != null) {
                val parts = deviceIp.split('.')
                if (parts.size == 4) add("${parts[0]}.${parts[1]}.${parts[2]}.255")
            }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (mac in macs) {
                val macBytes = parseMac(mac) ?: continue
                val packet = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (rep in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
                for (target in targets) {
                    val address = runCatching { InetAddress.getByName(target) }.getOrNull() ?: continue
                    for (port in intArrayOf(9, 7)) {
                        runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }
                    }
                }
            }
        }
    }
}
