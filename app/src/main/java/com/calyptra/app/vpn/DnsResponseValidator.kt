package com.calyptra.app.vpn

/**
 * Validates that an upstream DNS response plausibly answers the query we sent.
 *
 * Allowed queries are forwarded to an upstream resolver over UDP and the answer
 * is written straight back into the TUN interface. A blind/off-path attacker who
 * can guess the 16-bit transaction id could otherwise inject a forged answer
 * (cache-poisoning / redirection). Beyond the id + QR bit, we require the
 * response to echo our exact question section — which RFC 1035 §4.1.2 resolvers
 * always copy verbatim — shrinking the spoofing window from 2^16 to the full
 * entropy of the question (QNAME + QTYPE + QCLASS).
 *
 * Pure and stateless so it can be unit-tested without the Android VPN stack.
 */
internal object DnsResponseValidator {

    private const val HEADER_LEN = 12

    /** True when [response] is a plausible answer to the query [query] we sent. */
    fun matches(query: ByteArray, response: ByteArray): Boolean {
        if (query.size < HEADER_LEN || response.size < HEADER_LEN) return false

        // Transaction id (bytes 0-1) must match.
        if (query[0] != response[0] || query[1] != response[1]) return false

        // QR bit (0x80 of byte 2) must indicate a response.
        if ((response[2].toInt() and 0x80) == 0) return false

        // QDCOUNT (bytes 4-5) must be >= 1 and identical in both packets.
        val questionCount = u16(query, 4)
        if (questionCount < 1 || questionCount != u16(response, 4)) return false

        // The response must echo our question section byte-for-byte.
        val questionEnd = questionSectionEnd(query, questionCount) ?: return false
        if (response.size < questionEnd) return false
        for (i in HEADER_LEN until questionEnd) {
            if (query[i] != response[i]) return false
        }
        return true
    }

    /**
     * Offset just past the last question in [packet], or null if the question
     * section is malformed. A query never uses name compression in its question
     * (there is nothing earlier to point at), so any pointer/reserved bits in a
     * label length are treated as malformed rather than followed.
     */
    private fun questionSectionEnd(packet: ByteArray, questionCount: Int): Int? {
        var offset = HEADER_LEN
        repeat(questionCount) {
            // QNAME: length-prefixed labels terminated by a zero-length label.
            while (true) {
                if (offset >= packet.size) return null
                val length = packet[offset].toInt() and 0xFF
                if (length == 0) {
                    offset++
                    break
                }
                if ((length and 0xC0) != 0) return null // compression / reserved bits
                offset += 1 + length
                if (offset > packet.size) return null
            }
            // QTYPE (2) + QCLASS (2).
            offset += 4
            if (offset > packet.size) return null
        }
        return offset
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
