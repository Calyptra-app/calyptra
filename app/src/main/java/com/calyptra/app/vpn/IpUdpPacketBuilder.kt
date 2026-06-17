package com.calyptra.app.vpn

import java.nio.ByteBuffer

/**
 * Builds the synthetic IPv4 + UDP DNS response packets the VPN injects back into
 * the TUN interface to answer blocked / redirected / forwarded queries.
 *
 * A bug in the header assembly or in either checksum would make the OS (or the
 * client's stack) silently drop the packet, breaking blocking with no visible
 * error — exactly the kind of bit-twiddling that regresses quietly. Extracted
 * from [AdBlockVpnService] so it can be unit-tested in isolation; the logic is
 * unchanged from when it lived inline on the service.
 */
object IpUdpPacketBuilder {

    /**
     * Rewrites [request] (an intercepted IPv4/UDP DNS query) into a response
     * carrying [dnsPayload]: swaps the src/dst IPs and ports, fixes the IP and
     * UDP length fields, copies the payload, and recomputes both checksums.
     * [ipHeaderLength] is the byte length of the IPv4 header (20 without options).
     */
    fun constructIpUdpResponse(request: ByteBuffer, dnsPayload: ByteBuffer, ipHeaderLength: Int): ByteArray {
        val requestArray = request.array()

        val srcIpOffset = 12
        val dstIpOffset = 16
        val srcPortOffset = ipHeaderLength
        val dstPortOffset = ipHeaderLength + 2
        val udpLengthOffset = ipHeaderLength + 4
        val udpChecksumOffset = ipHeaderLength + 6

        val totalLength = ipHeaderLength + 8 + dnsPayload.remaining()
        val response = ByteArray(totalLength)

        // Copy IP Header
        System.arraycopy(requestArray, 0, response, 0, ipHeaderLength)

        // Fix IP Length
        response[2] = ((totalLength shr 8) and 0xFF).toByte()
        response[3] = (totalLength and 0xFF).toByte()

        // Swap IPs
        System.arraycopy(requestArray, srcIpOffset, response, dstIpOffset, 4)
        System.arraycopy(requestArray, dstIpOffset, response, srcIpOffset, 4)

        // Reset Checksum for calculation
        response[10] = 0
        response[11] = 0
        val ipChecksum = calculateChecksum(response, 0, ipHeaderLength)
        response[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()

        // Copy UDP Header
        System.arraycopy(requestArray, ipHeaderLength, response, ipHeaderLength, 8)

        // Swap Ports
        System.arraycopy(requestArray, srcPortOffset, response, dstPortOffset, 2)
        System.arraycopy(requestArray, dstPortOffset, response, srcPortOffset, 2)

        // Fix UDP Length
        val udpLength = 8 + dnsPayload.remaining()
        response[udpLengthOffset] = ((udpLength shr 8) and 0xFF).toByte()
        response[udpLengthOffset + 1] = (udpLength and 0xFF).toByte()

        // Copy DNS Payload
        val dnsBytes = ByteArray(dnsPayload.remaining())
        dnsPayload.get(dnsBytes)
        System.arraycopy(dnsBytes, 0, response, ipHeaderLength + 8, dnsBytes.size)

        // UDP Checksum calculation
        response[udpChecksumOffset] = 0
        response[udpChecksumOffset + 1] = 0
        val udpChecksum = calculateUdpChecksum(response, ipHeaderLength, udpLength)
        response[udpChecksumOffset] = ((udpChecksum shr 8) and 0xFF).toByte()
        response[udpChecksumOffset + 1] = (udpChecksum and 0xFF).toByte()

        return response
    }

    /** One's-complement Internet checksum (RFC 1071) over [length] bytes of
     *  [data] starting at [offset]. */
    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    /** UDP checksum over the IPv4 pseudo-header (src/dst IP at bytes 12-19,
     *  protocol 17, UDP length) plus the UDP header and payload at [udpOffset]
     *  for [udpLength] bytes. A computed value of 0 is sent as 0xFFFF per RFC 768. */
    fun calculateUdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0
        // Pseudo Header: Src IP(4), Dst IP(4), Zero(1), Proto(1), UDPLen(2)
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += 17 // Protocol UDP
        sum += udpLength

        // UDP Header + Payload
        for (i in 0 until udpLength step 2) {
            if (i + 1 >= udpLength) {
                sum += (packet[udpOffset + i].toInt() and 0xFF) shl 8
            } else {
                sum += ((packet[udpOffset + i].toInt() and 0xFF) shl 8) or (packet[udpOffset + i + 1].toInt() and 0xFF)
            }
        }

        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        var finalSum = sum.inv() and 0xFFFF
        if (finalSum == 0) finalSum = 0xFFFF
        return finalSum
    }
}
