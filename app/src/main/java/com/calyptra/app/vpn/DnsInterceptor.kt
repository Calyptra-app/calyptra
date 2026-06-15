package com.calyptra.app.vpn

import java.nio.ByteBuffer

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

    fun processDnsPacket(packet: ByteBuffer): ByteBuffer? {
        // Simple DNS parsing logic
        // Return null if allowed (to be forwarded)
        // Return ByteBuffer with response if blocked
        
        try {
            val position = packet.position()
            val limit = packet.limit()
            val data = ByteArray(limit - position)
            packet.get(data)
            packet.position(position) // Reset for forwarding if needed

            if (data.size < 12) return null // Too short

            // Parse Header
            // ID: bytes 0-1
            // Flags: bytes 2-3
            val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            
            if (qdCount == 0) return null

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
                    // Compression pointer, not expected in query name usually
                    return null 
                }
                offset++
                if (offset + length > data.size) return null
                
                for (i in 0 until length) {
                    domainBuilder.append(data[offset + i].toChar())
                }
                domainBuilder.append('.')
                offset += length
            }
            
            if (domainBuilder.isNotEmpty()) {
                domainBuilder.setLength(domainBuilder.length - 1) // Remove trailing dot
            }
            
            val domain = domainBuilder.toString()

            // Type and Class (4 bytes)
            if (offset + 4 > data.size) return null

            // Parse query type (A=1, AAAA=28).
            val queryType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

            // Block DoH canary and known DoH provider domains to prevent browsers bypassing DNS blocking.
            // Checked before any policy verdict — this must not be overridable.
            if (DOH_CANARY_DOMAINS.contains(domain.lowercase())) {
                return constructNxdomainResponse(request = data, questionEnd = offset + 4)
            }

            return when (val verdict = resolveVerdict(domain, queryType)) {
                Verdict.Allow -> null // Forward upstream
                Verdict.BlockZeroIp -> constructBlockedResponse(request = data, questionEnd = offset + 4)
                Verdict.BlockNxdomain -> constructNxdomainResponse(request = data, questionEnd = offset + 4)
                Verdict.Nodata -> constructNodataResponse(request = data, questionEnd = offset + 4)
                is Verdict.Redirect -> constructRedirectResponse(request = data, questionEnd = offset + 4, ip = verdict.ip)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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