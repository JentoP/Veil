package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal object Zeus {
    private const val ARTEMIS = "com.limelight.noir"
    private const val APOLLO_PORT = 47989
    private val mac = BuildConfig.ZEUS_MAC.replace(":", "").replace("-", "")
        .takeIf { it.matches(Regex("[0-9a-fA-F]{12}")) }
        ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    fun matches(packageName: String): Boolean =
        packageName == ARTEMIS && BuildConfig.ZEUS_HOST.isNotBlank() && mac != null

    fun reachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(BuildConfig.ZEUS_HOST, APOLLO_PORT), 750) }
        true
    }.getOrDefault(false)

    fun wake(): Boolean {
        val address = mac ?: return false
        val packet = ByteArray(6 + 16 * address.size) { 0xff.toByte() }
        repeat(16) { address.copyInto(packet, 6 + it * address.size) }

        return runCatching {
            val broadcast = BuildConfig.ZEUS_BROADCAST.ifBlank { "255.255.255.255" }
            DatagramSocket().use {
                it.broadcast = true
                it.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), 9))
            }
            true
        }.getOrDefault(false)
    }
}
