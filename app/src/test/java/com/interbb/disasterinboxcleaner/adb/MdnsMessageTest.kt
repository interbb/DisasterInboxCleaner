package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built DNS-message byte fixtures for [MdnsMessage], no socket involved. */
class MdnsMessageTest {

    @Test
    fun srvPortIsFoundThroughACompressedName() {
        val bytes = mutableListOf<Byte>()
        bytes += header(qd = 0, an = 2, ns = 0, ar = 0)

        // Answer 1: a PTR record whose rdata holds the instance name directly (uncompressed) —
        // this is the name the SRV record below will point back into, exactly as a real mDNS
        // response reuses the instance name across records.
        bytes += labels("_adb-tls-pairing._tcp.local")
        bytes += u16(12) // TYPE PTR
        bytes += u16(1) // CLASS IN
        bytes += u32(120) // TTL
        val instanceName = labels("adb-1a2b3c._adb-tls-pairing._tcp.local")
        bytes += u16(instanceName.size) // RDLENGTH
        val instanceOffset = bytes.size
        bytes += instanceName

        // Answer 2: an SRV record whose owner name is a compression pointer back into answer 1's
        // rdata.
        bytes += pointerTo(instanceOffset)
        bytes += u16(33) // TYPE SRV
        bytes += u16(0x8001) // CLASS (cache-flush bit set) + IN
        bytes += u32(120) // TTL
        val srvRdata = u16(0) + u16(0) + u16(PAIRING_PORT) + listOf(0.toByte()) // priority, weight, port, root target
        bytes += u16(srvRdata.size)
        bytes += srvRdata

        val packet = bytes.toByteArray()
        val result = MdnsMessage.srvPorts(packet, packet.size)

        assertEquals(mapOf(MdnsDiscovery.PAIRING_TYPE to PAIRING_PORT), result)
    }

    @Test
    fun bothServiceTypesAreCollectedFromOneMessage() {
        val bytes = mutableListOf<Byte>()
        bytes += header(qd = 0, an = 2, ns = 0, ar = 0)

        bytes += labels("adb-x._adb-tls-pairing._tcp.local")
        bytes += u16(33)
        bytes += u16(1)
        bytes += u32(120)
        val srv1 = u16(0) + u16(0) + u16(PAIRING_PORT) + listOf(0.toByte())
        bytes += u16(srv1.size)
        bytes += srv1

        bytes += labels("adb-y._adb-tls-connect._tcp.local")
        bytes += u16(33)
        bytes += u16(1)
        bytes += u32(120)
        val srv2 = u16(0) + u16(0) + u16(CONNECT_PORT) + listOf(0.toByte())
        bytes += u16(srv2.size)
        bytes += srv2

        val packet = bytes.toByteArray()
        val result = MdnsMessage.srvPorts(packet, packet.size)

        assertEquals(
            mapOf(MdnsDiscovery.PAIRING_TYPE to PAIRING_PORT, MdnsDiscovery.CONNECT_TYPE to CONNECT_PORT),
            result,
        )
    }

    @Test
    fun truncatedOrGarbagePacketsYieldAnEmptyMapAndNeverThrow() {
        assertTrue(MdnsMessage.srvPorts(ByteArray(0), 0).isEmpty())
        assertTrue(MdnsMessage.srvPorts(ByteArray(5), 5).isEmpty())

        // A header that claims far more records than the (short) buffer actually contains.
        val bogus = (header(qd = 5, an = 20, ns = 20, ar = 20) + labels("a.b")).toByteArray()
        assertTrue(MdnsMessage.srvPorts(bogus, bogus.size).isEmpty())

        // Arbitrary non-DNS bytes must not throw regardless of what (if anything) is "found".
        val random = ByteArray(200) { ((it * 73 + 11) and 0xFF).toByte() }
        MdnsMessage.srvPorts(random, random.size)
    }

    @Test(timeout = 2_000)
    fun nameCompressionLoopCannotHang() {
        val bytes = mutableListOf<Byte>()
        bytes += header(qd = 0, an = 2, ns = 0, ar = 0)

        // Record 1's owner name is a pointer to itself.
        val selfPointerOffset = bytes.size
        bytes += pointerTo(selfPointerOffset)
        bytes += u16(33)
        bytes += u16(1)
        bytes += u32(0)
        bytes += u16(0) // RDLENGTH 0

        // Record 2's owner name points forward past the end of the message — never valid in real
        // DNS compression, but a crafted packet could still try it to force a cycle.
        val record2Offset = bytes.size
        bytes += pointerTo(record2Offset + 500)
        bytes += u16(33)
        bytes += u16(1)
        bytes += u32(0)
        bytes += u16(0)

        val packet = bytes.toByteArray()
        // Returning at all, within the JUnit timeout above, is the assertion.
        MdnsMessage.srvPorts(packet, packet.size)
    }

    @Test(timeout = 2_000)
    fun alternatingBackwardPointersCannotHang() {
        val bytes = mutableListOf<Byte>()
        bytes += header(qd = 0, an = 1, ns = 0, ar = 0)

        // The owner name of this one answer record starts right after the header: a 2-byte label
        // that runs forward into a pointer which jumps straight back to the start of that same
        // label. Every jump in this cycle is strictly backward (the pointer's target is always
        // less than the offset it was read from), which is the only rule MdnsMessage's readName
        // enforces on a pointer — so that rule alone does NOT bound this walk; only the step
        // counter (MAX_NAME_STEPS) does. This is the alternating-pointer shape from the round-5
        // review's adversarial analysis (there illustrated as offset 20 -> 13 -> 10 -> 13 -> ...).
        val nameStart = bytes.size
        bytes += listOf(2.toByte(), 'x'.code.toByte(), 'y'.code.toByte()) // 2-byte label "xy"
        bytes += pointerTo(nameStart) // jumps back into the label, forever

        val packet = bytes.toByteArray()
        // Returning at all, within the JUnit timeout above, is the assertion.
        MdnsMessage.srvPorts(packet, packet.size)
    }

    @Test
    fun ownerNameMerelyContainingTheServiceTypeIsNotAMatch() {
        val bytes = mutableListOf<Byte>()
        bytes += header(qd = 0, an = 1, ns = 0, ar = 0)

        // A forged instance name that contains the pairing service type as a substring but does
        // not end with it (I5): the real type is followed by more of the "domain" the attacker
        // controls, e.g. as a subdomain trick.
        bytes += labels("evil._adb-tls-pairing._tcp.local.attacker.example")
        bytes += u16(33)
        bytes += u16(1)
        bytes += u32(120)
        val srv = u16(0) + u16(0) + u16(PAIRING_PORT) + listOf(0.toByte())
        bytes += u16(srv.size)
        bytes += srv

        val packet = bytes.toByteArray()
        val result = MdnsMessage.srvPorts(packet, packet.size)

        assertTrue(result.isEmpty())
    }

    private companion object {
        const val PAIRING_PORT = 40123
        const val CONNECT_PORT = 50321
    }

    private fun header(qd: Int, an: Int, ns: Int, ar: Int): List<Byte> =
        listOf<Byte>(0, 0, 0, 0) + u16(qd) + u16(an) + u16(ns) + u16(ar)

    private fun labels(name: String): List<Byte> {
        val out = mutableListOf<Byte>()
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            out += label.length.toByte()
            out += label.toByteArray(Charsets.UTF_8).toList()
        }
        out += 0
        return out
    }

    private fun pointerTo(offset: Int): List<Byte> =
        listOf((0xC0 or ((offset shr 8) and 0x3F)).toByte(), (offset and 0xFF).toByte())

    private fun u16(v: Int): List<Byte> = listOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u32(v: Int): List<Byte> = listOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )
}
