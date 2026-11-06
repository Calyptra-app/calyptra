package com.calyptra.app.vpn

import java.nio.ByteBuffer

/**
 * Outcome of intercepting one DNS query.
 *
 * @property response the locally generated answer, or null to forward upstream.
 * @property countsAsBlocked true only for a real blocking decision: ad/tracker
 *   sinkhole, threat/category NXDOMAIN, or the DoH canary. SafeSearch redirects
 *   and the NODATA answers that go with them are rewrites, not blocks (#67).
 */
class DnsInterceptResult(
    val response: ByteBuffer?,
    val countsAsBlocked: Boolean,
    /** Forward to the malware-only allowlist resolver, not the family filter. */
    val unfiltered: Boolean = false,
) {
    companion object {
        val FORWARD = DnsInterceptResult(null, countsAsBlocked = false)
        val FORWARD_UNFILTERED = DnsInterceptResult(null, countsAsBlocked = false, unfiltered = true)
        fun blocked(response: ByteBuffer) = DnsInterceptResult(response, countsAsBlocked = true)
        fun rewritten(response: ByteBuffer) = DnsInterceptResult(response, countsAsBlocked = false)
    }
}

class DnsInterceptor(
    private val resolveVerdict: (domain: String, queryType: Int) -> Verdict
) {

    companion object {
        private val DOH_CANARY_DOMAINS = setOf(
            "use-application-dns.net",
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "doh.opendns.com",
            "dns.quad9.net",
            "doh.cleanbrowsing.org"
        )
    }

    /** Response bytes only. See [intercept] for whether the answer is a block. */
    fun processDnsPacket(packet: ByteBuffer): ByteBuffer? = intercept(packet).response

    /**
     * Parses the query and decides what to do with it. A null
     * [DnsInterceptResult.response] means forward upstream; otherwise it is the
     * locally generated answer.
     */
    fun intercept(packet: ByteBuffer): DnsInterceptResult {
        try {
            val position = packet.position()
            val limit = packet.limit()
            val data = ByteArray(limit - position)
            packet.get(data)
            packet.position(position) // Reset for forwarding if needed

            if (data.size < 12) return DnsInterceptResult.FORWARD // Too short

            // Parse Header
            // ID: bytes 0-1
            // Flags: bytes 2-3
            val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            
            if (qdCount == 0) return DnsInterceptResult.FORWARD

            // Parse Question Section (starts at byte 12)
            var offset = 12
            val domainBuilder = StringBuilder()
            
            while (offset < data.size) {
                val length = data[offset].toInt() and 0xFF
                if (length == 0) {
                    offset++
                    break // End of name
                }
                if ((length and 0xC0) == 0xC0) {
                    // A query never legitimately compresses its own question name
                    // (there is nothing earlier to point at). A 0xC0 pointer here
                    // is a crafted packet trying to slip an unparseable QNAME past
                    // the blocklist/threat filter — which previously returned null
                    // (forward/allow). Fail CLOSED instead: answer NXDOMAIN,
                    // echoing the client's own bytes since we can't parse the name.
                    // Not counted as a block: this is parser hardening against a
                    // malformed packet, not a decision about a site the child
                    // tried to reach, and junk traffic shouldn't inflate stats.
                    return DnsInterceptResult.rewritten(
                        constructNxdomainResponse(request = data, questionEnd = data.size)
                    )
                }
                offset++
                if (offset + length > data.size) return DnsInterceptResult.FORWARD

                for (i in 0 until length) {
                    domainBuilder.append(data[offset + i].toChar())
                }
                domainBuilder.append('.')
                offset += length

                // Cap the assembled name at the DNS maximum (253 bytes). A crafted
                // multi-KB QNAME must never reach the matcher; bail to the
                // fall-through (null == forward/allow) instead.
                if (domainBuilder.length > 253) return DnsInterceptResult.FORWARD
            }
            
            if (domainBuilder.isNotEmpty()) {
                domainBuilder.setLength(domainBuilder.length - 1) // Remove trailing dot
            }
            
            val domain = domainBuilder.toString()

            // Type and Class (4 bytes)
            if (offset + 4 > data.size) return DnsInterceptResult.FORWARD

            // Parse query type (A=1, AAAA=28).
            val queryType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

            // Block DoH canary and known DoH provider domains to prevent browsers bypassing DNS blocking.
            // Checked before any policy verdict — this must not be overridable.
            if (DOH_CANARY_DOMAINS.contains(domain.lowercase())) {
                return DnsInterceptResult.blocked(
                    constructNxdomainResponse(request = data, questionEnd = offset + 4)
                )
            }

            val questionEnd = offset + 4
            return when (val verdict = resolveVerdict(domain, queryType)) {
                Verdict.Allow -> DnsInterceptResult.FORWARD
                Verdict.AllowUnfiltered -> DnsInterceptResult.FORWARD_UNFILTERED
                Verdict.BlockZeroIp -> DnsInterceptResult.blocked(
                    constructBlockedResponse(request = data, questionEnd = questionEnd)
                )
                Verdict.BlockNxdomain -> DnsInterceptResult.blocked(
                    constructNxdomainResponse(request = data, questionEnd = questionEnd)
                )
                // DnsPolicy only emits Nodata for SafeSearch (AAAA on a redirected
                // domain, forcing the IPv4 redirect), so it is a rewrite, not a block.
                Verdict.Nodata -> DnsInterceptResult.rewritten(
                    constructNodataResponse(request = data, questionEnd = questionEnd)
                )
                is Verdict.Redirect -> DnsInterceptResult.rewritten(
                    constructRedirectResponse(request = data, questionEnd = questionEnd, ip = verdict.ip)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return DnsInterceptResult.FORWARD
        }
    }

    private fun constructBlockedResponse(request: ByteArray, questionEnd: Int): ByteBuffer {
        // Copy ID
        // Set QR=1, AA=1, RA=1. Flags: 0x8180 (Standard Query Response, No Error)
        // ANCOUNT = 1
        
        val responseSize = questionEnd + 16 // Header + Question + Resource Record
        val response = ByteBuffer.allocate(responseSize)
        
        // Header
        response.put(request, 0, 2) // ID
        response.putShort(0x8180.toShort()) // Flags: QR, AA, RA
        response.putShort(0x0001) // QDCOUNT = 1
        response.putShort(0x0001) // ANCOUNT = 1
        response.putShort(0x0000) // NSCOUNT = 0
        response.putShort(0x0000) // ARCOUNT = 0
        
        // Question
        response.put(request, 12, questionEnd - 12)
        
        // Answer (A Record 0.0.0.0)
        // Name: Pointer to question name (0xC00C -> offset 12)
        response.putShort(0xC00C.toShort())
        response.putShort(0x0001) // Type A
        response.putShort(0x0001) // Class IN
        response.putInt(60)       // TTL
        response.putShort(4)      // RDLength (4 bytes for IPv4)
        response.putInt(0)        // 0.0.0.0
        
        response.flip()
        return response
    }

    private fun constructRedirectResponse(request: ByteArray, questionEnd: Int, ip: ByteArray): ByteBuffer {
        val responseSize = questionEnd + 16 // Header + Question + Answer RR
        val response = ByteBuffer.allocate(responseSize)

        // Header
        response.put(request, 0, 2) // ID
        response.putShort(0x8180.toShort()) // Flags: QR, AA, RA
        response.putShort(0x0001) // QDCOUNT = 1
        response.putShort(0x0001) // ANCOUNT = 1
        response.putShort(0x0000) // NSCOUNT = 0
        response.putShort(0x0000) // ARCOUNT = 0

        // Question section
        response.put(request, 12, questionEnd - 12)

        // Answer (A Record pointing to SafeSearch IP)
        response.putShort(0xC00C.toShort()) // Name pointer
        response.putShort(0x0001) // Type A
        response.putShort(0x0001) // Class IN
        response.putInt(300)      // TTL 5 min
        response.putShort(4)      // RDLength
        response.put(ip)          // SafeSearch IP

        response.flip()
        return response
    }

    private fun constructNodataResponse(request: ByteArray, questionEnd: Int): ByteBuffer {
        val response = ByteBuffer.allocate(questionEnd)

        response.put(request, 0, 2)           // ID
        response.putShort(0x8180.toShort())    // Flags: QR=1, AA=1, RA=1, RCODE=0 (no error, just no data)
        response.putShort(0x0001)              // QDCOUNT = 1
        response.putShort(0x0000)              // ANCOUNT = 0
        response.putShort(0x0000)              // NSCOUNT = 0
        response.putShort(0x0000)              // ARCOUNT = 0
        response.put(request, 12, questionEnd - 12) // Question section

        response.flip()
        return response
    }

    private fun constructNxdomainResponse(request: ByteArray, questionEnd: Int): ByteBuffer {
        val response = ByteBuffer.allocate(questionEnd)
        response.put(request, 0, 2)           // ID
        response.putShort(0x8183.toShort())    // Flags: QR=1, AA=1, RA=1, RCODE=3 (NXDOMAIN)
        response.putShort(0x0001)              // QDCOUNT = 1
        response.putShort(0x0000)              // ANCOUNT = 0
        response.putShort(0x0000)              // NSCOUNT = 0
        response.putShort(0x0000)              // ARCOUNT = 0
        response.put(request, 12, questionEnd - 12) // Question section
        response.flip()
        return response
    }
}