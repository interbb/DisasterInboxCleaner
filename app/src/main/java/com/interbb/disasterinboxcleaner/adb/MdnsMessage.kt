package com.interbb.disasterinboxcleaner.adb

/**
 * Pure DNS-message reader for the SRV records this app cares about. No Android dependency, so it
 * is unit-tested directly against hand-built byte fixtures (see `MdnsMessageTest`).
 *
 * Defensive against malformed or maliciously compressed input: [srvPorts] never throws (any
 * out-of-bounds read is caught and whatever was already parsed is returned). Name-compression
 * pointer chains cannot loop forever, but *not* because a pointer is only followed when its target
 * is strictly less than the offset it was read from — that rule alone permits an alternating
 * backward chain (e.g. offset 20 -> 13 -> 10 -> 13 -> ...) to revisit the same offsets forever,
 * since every jump in it still lands strictly earlier than where it was read from. What actually
 * bounds the walk is the explicit step counter, [MAX_NAME_STEPS]: every step (label or pointer
 * jump) counts against it, so a name can never take more steps than that regardless of how its
 * pointers are arranged. Per-record work is further bounded by capping the assembled name at DNS's
 * own 255-byte limit.
 */
internal object MdnsMessage {
    private const val TYPE_SRV = 33
    private const val MAX_NAME_STEPS = 128
    private const val MAX_RECORDS = 512
    private const val MAX_NAME_LENGTH = 255

    /** Maps [MdnsDiscovery.PAIRING_TYPE] / [MdnsDiscovery.CONNECT_TYPE] to the SRV port found for it, if any. */
    fun srvPorts(packet: ByteArray, length: Int): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        runCatching {
            if (length < 12) return@runCatching
            fun u16(at: Int): Int {
                if (at < 0 || at + 1 >= length) throw IndexOutOfBoundsException()
                return ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)
            }

            fun skipName(from: Int): Int {
                var i = from
                var steps = 0
                while (i < length && steps++ < MAX_NAME_STEPS) {
                    val b = packet[i].toInt() and 0xFF
                    if (b == 0) return i + 1
                    if (b and 0xC0 == 0xC0) return i + 2
                    i += b + 1
                }
                return i
            }

            fun readName(from: Int): Pair<String, Int> {
                val sb = StringBuilder()
                var i = from
                var jumped = false
                var after = from
                var steps = 0
                while (i in 0 until length && steps++ < MAX_NAME_STEPS) {
                    val b = packet[i].toInt() and 0xFF
                    if (b == 0) {
                        if (!jumped) after = i + 1
                        break
                    }
                    if (b and 0xC0 == 0xC0) {
                        if (i + 1 >= length) break
                        if (!jumped) after = i + 2
                        val target = ((b and 0x3F) shl 8) or (packet[i + 1].toInt() and 0xFF)
                        // A pointer must land strictly earlier than where it was read; this blocks
                        // a self-pointer or a forward jump into a cycle, but it does NOT by itself
                        // bound the walk — an alternating backward chain (e.g. 13 -> 10 -> 13 -> ...)
                        // satisfies this rule on every jump and would still cycle forever. Only the
                        // step counter above (MAX_NAME_STEPS) actually terminates that case.
                        if (target >= i) break
                        i = target
                        jumped = true
                        continue
                    }
                    val labelEnd = i + 1 + b
                    if (labelEnd > length) break
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(packet, i + 1, b, Charsets.UTF_8))
                    i = labelEnd
                    // DNS caps a full name at 255 bytes; stop assembling once a maliciously long
                    // (or endlessly cycling) chain of labels exceeds that, bounding per-record work.
                    if (sb.length > MAX_NAME_LENGTH) break
                }
                return sb.toString() to after
            }

            val qdCount = u16(4)
            val anCount = u16(6)
            val nsCount = u16(8)
            val arCount = u16(10)

            var pos = 12
            repeat(qdCount.coerceAtMost(MAX_RECORDS)) {
                pos = skipName(pos) + 4
            }
            repeat((anCount + nsCount + arCount).coerceAtMost(MAX_RECORDS)) {
                if (pos >= length) return@repeat
                val (name, next) = readName(pos)
                val type = u16(next)
                val rdlen = u16(next + 8)
                val rdata = next + 10
                if (type == TYPE_SRV && rdlen >= 6 && rdata + 6 <= length) {
                    val port = u16(rdata + 4)
                    when {
                        ownerMatchesServiceType(name, MdnsDiscovery.PAIRING_TYPE) -> out[MdnsDiscovery.PAIRING_TYPE] = port
                        ownerMatchesServiceType(name, MdnsDiscovery.CONNECT_TYPE) -> out[MdnsDiscovery.CONNECT_TYPE] = port
                    }
                }
                pos = rdata + rdlen
            }
        }
        return out
    }

    /**
     * True when [name] is an instance of [serviceType] — its owner name ends with the service
     * type, optionally followed by a single trailing dot (the root label some responders include).
     * A mere substring match would let `evil._adb-tls-pairing._tcp.local.attacker.example` count
     * as `_adb-tls-pairing._tcp.local` (I5); requiring the match at the end rejects that while still
     * accepting any real instance name, which is always `<instance>.<serviceType>[.]`.
     */
    private fun ownerMatchesServiceType(name: String, serviceType: String): Boolean =
        name.endsWith(serviceType) || name.endsWith("$serviceType.")
}
