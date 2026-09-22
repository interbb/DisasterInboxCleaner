package com.interbb.disasterinboxcleaner.adb

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** The wireless-debugging service ports found on the phone itself, or null if not found. */
data class AdbPorts(val pairingPort: Int?, val connectPort: Int?)

/**
 * Finds the phone's own `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` mDNS service ports with
 * a plain unicast-response query: one ephemeral [DatagramSocket], no `NsdManager` (its system
 * picker shows nothing useful on this device), no `MulticastLock`, no group join, no extra
 * permission. Verified against the phone's real pairing dialog port by the app-side mDNS spike
 * (see progress.md's "Self-mDNS spike" section).
 *
 * Requires `targetSdk` 35 or lower: at 36+, Android 16 Local Network Protection blocks the send
 * with `EPERM` via the `ACCESS_LOCAL_NETWORK` app-op, which cannot be granted from shell.
 */
object MdnsDiscovery {
    const val PAIRING_TYPE = "_adb-tls-pairing._tcp.local"
    const val CONNECT_TYPE = "_adb-tls-connect._tcp.local"

    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val SOCKET_READ_TIMEOUT_MS = 1_000
    private val VALID_PORT_RANGE = 1024..65535

    /**
     * Blocking; call off the main thread. Sends one PTR query per service type with the
     * unicast-response bit set in QCLASS, re-sends both once mid-way through [timeoutMs] (mDNS
     * replies can be lost), and returns whatever SRV ports it collected. Never throws: any
     * failure (no network, socket error, ...) yields `AdbPorts(null, null)`.
     */
    fun discover(timeoutMs: Long = 4_000): AdbPorts = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            val group = InetAddress.getByName(MDNS_GROUP)
            val types = listOf(PAIRING_TYPE, CONNECT_TYPE)

            fun sendQueries() {
                types.forEach { type ->
                    runCatching {
                        val query = buildQuery(type)
                        socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                    }
                }
            }

            val trustedAddresses = localAddresses(socket)

            val start = System.currentTimeMillis()
            val deadline = start + timeoutMs
            val resendAt = start + timeoutMs / 2
            var resent = false
            sendQueries()

            var pairingPort: Int? = null
            var connectPort: Int? = null
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline && (pairingPort == null || connectPort == null)) {
                if (!resent && System.currentTimeMillis() >= resendAt) {
                    resent = true
                    sendQueries()
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                // I5: only trust a reply that actually came from this device — the query goes out
                // over LAN multicast, so any host on the same Wi-Fi could otherwise race a forged
                // response to the ephemeral source port.
                if (packet.address !in trustedAddresses) continue
                val found = MdnsMessage.srvPorts(packet.data, packet.length)
                found[PAIRING_TYPE]?.takeIf { it in VALID_PORT_RANGE }?.let { pairingPort = it }
                found[CONNECT_TYPE]?.takeIf { it in VALID_PORT_RANGE }?.let { connectPort = it }
            }
            AdbPorts(pairingPort, connectPort)
        }
    }.getOrDefault(AdbPorts(null, null))

    /**
     * All of this device's own addresses (loopback included), enumerated once per [discover] call
     * and used to reject a reply that did not actually come from this device (I5). Enumeration is
     * a plain local OS call with no extra permission needed for this app. If it fails for any
     * reason, falls back to loopback plus the address the socket itself is bound to, silently.
     */
    private fun localAddresses(socket: DatagramSocket): Set<InetAddress> = runCatching {
        val addresses = mutableSetOf<InetAddress>()
        NetworkInterface.getNetworkInterfaces().asSequence().forEach { iface ->
            iface.inetAddresses.asSequence().forEach { addresses += it }
        }
        addresses += InetAddress.getLoopbackAddress()
        addresses
    }.getOrElse {
        setOfNotNull(InetAddress.getLoopbackAddress(), socket.localAddress)
    }

    /** Builds a one-question mDNS PTR query for [name] with the unicast-response (QU) bit set. */
    private fun buildQuery(name: String): ByteArray {
        val body = mutableListOf<Byte>()
        body += listOf<Byte>(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) // id, flags, qd=1, an/ns/ar=0
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            body += label.length.toByte()
            body += label.toByteArray(Charsets.UTF_8).toList()
        }
        body += 0
        body += listOf<Byte>(0, 12) // QTYPE = PTR
        val qclass = 0x8001 // unicast-response bit set, class IN
        body += ((qclass shr 8) and 0xFF).toByte()
        body += (qclass and 0xFF).toByte()
        return body.toByteArray()
    }
}
