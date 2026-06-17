package com.calyptra.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class IpUdpPacketBuilderTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun word(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Canonical Internet-checksum validity check, computed independently of the
     *  code under test: the one's-complement sum of every 16-bit word over a
     *  region that already holds its checksum must fold to 0xFFFF. */
    private fun foldedSum(seed: Int, b: ByteArray, offset: Int, length: Int): Int {
        var sum = seed
        var i = offset
        while (i + 1 < offset + length) { sum += word(b, i); i += 2 }
        if (i < offset + length) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum
    }

    private fun ipChecksumValid(packet: ByteArray, ipHeaderLen: Int): Boolean =
        foldedSum(0, packet, 0, ipHeaderLen) == 0xFFFF

    private fun udpChecksumValid(packet: ByteArray, ipHeaderLen: Int, udpLen: Int): Boolean {
        // Pseudo-header: src+dst IP (bytes 12-19), protocol 17, UDP length.
        var pseudo = 17 + udpLen
        for (i in 12 until 20 step 2) pseudo += word(packet, i)
        return foldedSum(pseudo, packet, ipHeaderLen, udpLen) == 0xFFFF
    }

    // --- calculateChecksum against an independent reference ---

    @Test
    fun `calculateChecksum matches the known IPv4 header vector`() {
        // Classic worked example: this 20-byte IPv4 header checksums to 0xb861
        // (checksum field zeroed here for the computation).
        val header = bytes(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x11, 0x00, 0x00, // checksum field = 0
            0xc0, 0xa8, 0x00, 0x01, // 192.168.0.1
            0xc0, 0xa8, 0x00, 0xc7  // 192.168.0.199
        )
        assertEquals(0xb861, IpUdpPacketBuilder.calculateChecksum(header, 0, 20))
    }

    @Test
    fun `calculateChecksum handles an odd-length region (trailing byte padded high)`() {
        // 0x1234 + 0x5600 = 0x6834 -> complement 0x97cb.
        assertEquals(0x97cb, IpUdpPacketBuilder.calculateChecksum(bytes(0x12, 0x34, 0x56), 0, 3))
    }

    @Test
    fun `a header carrying its own checksum re-folds to 0xFFFF`() {
        val header = bytes(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x11, 0x00, 0x00,
            0xc0, 0xa8, 0x00, 0x01,
            0xc0, 0xa8, 0x00, 0xc7
        )
        val c = IpUdpPacketBuilder.calculateChecksum(header, 0, 20)
        header[10] = ((c shr 8) and 0xFF).toByte()
        header[11] = (c and 0xFF).toByte()
        assertTrue("header with checksum installed must be valid", ipChecksumValid(header, 20))
    }

    // --- constructIpUdpResponse: structure + both checksums valid ---

    /** A minimal intercepted IPv4/UDP DNS query (20-byte IP header, no options). */
    private fun request(payloadLen: Int): ByteArray {
        val ip = bytes(
            0x45, 0x00, 0x00, 0x00, 0x12, 0x34, 0x00, 0x00,
            0x40, 0x11, 0x00, 0x00,
            10, 0, 0, 2,            // src 10.0.0.2 (the client)
            10, 0, 0, 1             // dst 10.0.0.1 (our TUN resolver)
        )
        val udp = bytes(
            0xc3, 0x50,             // src port 50000
            0x00, 0x35,             // dst port 53
            0x00, 0x00,             // length (ignored on input)
            0x00, 0x00              // checksum (ignored on input)
        )
        val payload = ByteArray(payloadLen) { ((it + 1) and 0xFF).toByte() }
        return ip + udp + payload
    }

    private fun assertWellFormedResponse(reqPayloadLen: Int, respPayloadLen: Int) {
        val req = request(reqPayloadLen)
        val respPayload = ByteArray(respPayloadLen) { ((0xA0 + it) and 0xFF).toByte() }

        val out = IpUdpPacketBuilder.constructIpUdpResponse(
            ByteBuffer.wrap(req), ByteBuffer.wrap(respPayload), 20
        )

        val udpLen = 8 + respPayloadLen
        assertEquals("total size", 20 + udpLen, out.size)
        assertEquals("IP total-length field", out.size, word(out, 2))
        assertEquals("protocol preserved (UDP)", 0x11, out[9].toInt() and 0xFF)

        // IPs swapped: response src = request dst, response dst = request src.
        assertEquals("resp src IP == req dst IP", word(req, 16), word(out, 12))
        assertEquals("resp src IP == req dst IP", word(req, 18), word(out, 14))
        assertEquals("resp dst IP == req src IP", word(req, 12), word(out, 16))
        assertEquals("resp dst IP == req src IP", word(req, 14), word(out, 18))

        // Ports swapped.
        assertEquals("resp src port == req dst port", word(req, 22), word(out, 20))
        assertEquals("resp dst port == req src port", word(req, 20), word(out, 22))

        assertEquals("UDP length field", udpLen, word(out, 24))

        // Payload copied verbatim at offset 28 (20 IP + 8 UDP).
        for (i in 0 until respPayloadLen) {
            assertEquals("payload byte $i", respPayload[i], out[28 + i])
        }

        assertTrue("IP checksum must be valid", ipChecksumValid(out, 20))
        assertTrue("UDP checksum must be valid", udpChecksumValid(out, 20, udpLen))
    }

    @Test
    fun `constructIpUdpResponse builds a valid packet for an even-length payload`() {
        assertWellFormedResponse(reqPayloadLen = 12, respPayloadLen = 24)
    }

    @Test
    fun `constructIpUdpResponse builds a valid packet for an odd-length payload`() {
        // Odd UDP length exercises the trailing-byte branch of the UDP checksum.
        assertWellFormedResponse(reqPayloadLen = 9, respPayloadLen = 5)
    }
}
