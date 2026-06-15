package com.calyptra.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DnsInterceptorTest {

    /** Builds an interceptor wired the same way AdBlockVpnService wires it:
     *  one DnsPolicy resolver composing redirect, category, and ad-block checks. */
    private fun makeInterceptor(
        getRedirectIp: (String, Int) -> ByteArray? = { _, _ -> null },
        isCategoryBlocked: (String) -> Boolean = { false },
        isAdBlocked: (String) -> Boolean = { false }
    ) = DnsInterceptor { domain, queryType ->
        DnsPolicy.resolve(domain, queryType, getRedirectIp, isCategoryBlocked, isAdBlocked)
    }

    @Test
    fun `processDnsPacket returns response when domain is blocked`() {
        val interceptor = makeInterceptor(isAdBlocked = { domain -> domain == "blocked.com" })

        val query = createDnsQuery("blocked.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for blocked domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])

        // Verify answer count is 1 (bytes 6-7)
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x01.toByte(), responseBytes[7])
    }

    @Test
    fun `processDnsPacket returns NXDOMAIN for DoH canary domain`() {
        val interceptor = makeInterceptor()

        val query = createDnsQuery("use-application-dns.net")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for DoH canary", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // Flags byte 2 = 0x81 (QR=1), byte 3 = 0x83 (RA=1, RCODE=3)
        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])

        // ANCOUNT should be 0 (bytes 6-7)
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `processDnsPacket returns NXDOMAIN for dns_google`() {
        val interceptor = makeInterceptor()

        val query = createDnsQuery("dns.google")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for DoH provider domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `processDnsPacket returns NXDOMAIN for cloudflare-dns_com`() {
        val interceptor = makeInterceptor()

        val query = createDnsQuery("cloudflare-dns.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for DoH provider domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `processDnsPacket returns null when domain is allowed`() {
        val interceptor = makeInterceptor(isAdBlocked = { domain -> domain == "blocked.com" })

        val query = createDnsQuery("allowed.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNull("Response should be null for allowed domain", response)
    }

    @Test
    fun `processDnsPacket redirects google_com to SafeSearch IP`() {
        val safeSearchIp = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val interceptor = makeInterceptor(
            getRedirectIp = { domain, queryType ->
                if (domain == "google.com" && queryType == 1) safeSearchIp else null
            }
        )

        val query = createDnsQuery("google.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for redirected domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // Verify ANCOUNT = 1 (bytes 6-7)
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x01.toByte(), responseBytes[7])

        // Verify the A record contains SafeSearch IP (last 4 bytes of response)
        val ipStart = responseBytes.size - 4
        assertEquals(216.toByte(), responseBytes[ipStart])
        assertEquals(239.toByte(), responseBytes[ipStart + 1])
        assertEquals(38.toByte(), responseBytes[ipStart + 2])
        assertEquals(120.toByte(), responseBytes[ipStart + 3])
    }

    @Test
    fun `processDnsPacket redirects youtube_com to restrict IP`() {
        val restrictIp = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val interceptor = makeInterceptor(
            getRedirectIp = { domain, queryType ->
                if (domain == "www.youtube.com" && queryType == 1) restrictIp else null
            }
        )

        val query = createDnsQuery("www.youtube.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for YouTube redirect", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // ANCOUNT = 1
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x01.toByte(), responseBytes[7])
    }

    @Test
    fun `processDnsPacket does not redirect when SafeSearch disabled`() {
        val interceptor = makeInterceptor()

        val query = createDnsQuery("google.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNull("Response should be null when SafeSearch is disabled", response)
    }

    @Test
    fun `processDnsPacket returns NODATA for AAAA query on redirected domain`() {
        val interceptor = makeInterceptor(
            getRedirectIp = { domain, queryType ->
                if (domain == "google.com" && queryType == 28) byteArrayOf() else null
            }
        )

        val query = createDnsQuery("google.com", queryType = 28) // AAAA
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for AAAA NODATA", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // Flags: 0x8180 (no error)
        assertEquals(0x81.toByte(), responseBytes[2])
        assertEquals(0x80.toByte(), responseBytes[3])

        // ANCOUNT = 0 (bytes 6-7) — NODATA
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    // --- Verdict ordering (F11 / SOC-L2 foundation) ---

    @Test
    fun `category-blocked domain gets NXDOMAIN not zero IP`() {
        val interceptor = makeInterceptor(isCategoryBlocked = { domain -> domain == "tiktok.com" })

        val query = createDnsQuery("tiktok.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for category-blocked domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals("ANCOUNT must be 0 for NXDOMAIN", 0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `category block wins over ad blocklist when domain is in both`() {
        val interceptor = makeInterceptor(
            isCategoryBlocked = { domain -> domain == "both.com" },
            isAdBlocked = { domain -> domain == "both.com" }
        )

        val query = createDnsQuery("both.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull(response)
        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // NXDOMAIN (category), not 0.0.0.0 with ANCOUNT=1 (ad block)
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `redirect wins over category block`() {
        // YouTube-style domains must keep their restricted-mode redirect even if
        // a (miscurated) category set contains them.
        val ip = byteArrayOf(216.toByte(), 239.toByte(), 38, 119)
        val interceptor = makeInterceptor(
            getRedirectIp = { domain, _ -> if (domain == "www.youtube.com") ip else null },
            isCategoryBlocked = { true }
        )

        val query = createDnsQuery("www.youtube.com")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull(response)
        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // Redirect answer (ANCOUNT=1), not NXDOMAIN
        assertEquals(0x80.toByte(), responseBytes[3])
        assertEquals(0x01.toByte(), responseBytes[7])
        assertEquals(119.toByte(), responseBytes[responseBytes.size - 1])
    }

    @Test
    fun `DoH canary wins over redirect and blocklists`() {
        val interceptor = makeInterceptor(
            getRedirectIp = { _, _ -> byteArrayOf(1, 2, 3, 4) },
            isCategoryBlocked = { true },
            isAdBlocked = { true }
        )

        val query = createDnsQuery("dns.google")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull(response)
        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // Still NXDOMAIN with no answer record — canary check precedes everything.
        assertEquals(0x83.toByte(), responseBytes[3])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `DnsPolicy resolves Allow when nothing matches`() {
        val verdict = DnsPolicy.resolve(
            domain = "example.com", queryType = 1,
            getRedirectIp = { _, _ -> null },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `DnsPolicy resolves Nodata for empty redirect IP`() {
        val verdict = DnsPolicy.resolve(
            domain = "google.com", queryType = 28,
            getRedirectIp = { _, _ -> byteArrayOf() },
            isCategoryBlocked = { true },
            isAdBlocked = { true }
        )
        assertEquals(Verdict.Nodata, verdict)
    }

    private fun createDnsQuery(domain: String, queryType: Int = 1): ByteArray {
        val buffer = ByteBuffer.allocate(512)
        // Header
        buffer.putShort(0x1234) // ID
        buffer.putShort(0x0100) // Flags: RD=1
        buffer.putShort(0x0001) // QDCOUNT=1
        buffer.putShort(0x0000) // ANCOUNT=0
        buffer.putShort(0x0000) // NSCOUNT=0
        buffer.putShort(0x0000) // ARCOUNT=0

        // QNAME
        for (part in domain.split('.')) {
            buffer.put(part.length.toByte())
            for (char in part) {
                buffer.put(char.code.toByte())
            }
        }
        buffer.put(0.toByte()) // Terminating zero

        // QTYPE
        buffer.putShort(queryType.toShort())
        // QCLASS (IN = 1)
        buffer.putShort(1)

        val result = ByteArray(buffer.position())
        buffer.position(0)
        buffer.get(result)
        return result
    }
}
