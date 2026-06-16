package com.calyptra.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DnsInterceptorTest {

    /** Builds an interceptor wired the same way AdBlockVpnService wires it:
     *  one DnsPolicy resolver composing redirect, category, and ad-block checks. */
    private fun makeInterceptor(
        isDomainAllowed: (String) -> Boolean = { false },
        getRedirectIp: (String, Int) -> ByteArray? = { _, _ -> null },
        isThreatBlocked: (String) -> Boolean = { false },
        isCategoryBlocked: (String) -> Boolean = { false },
        isAdBlocked: (String) -> Boolean = { false }
    ) = DnsInterceptor { domain, queryType ->
        DnsPolicy.resolve(
            domain, queryType, isDomainAllowed, getRedirectIp,
            isThreatBlocked, isCategoryBlocked, isAdBlocked
        )
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

    // --- Threat (malware/phishing) always-on blocking ---

    @Test
    fun `threat-listed domain gets NXDOMAIN`() {
        val interceptor = makeInterceptor(isThreatBlocked = { domain -> domain == "malware.example" })

        val query = createDnsQuery("malware.example")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull("Response should not be null for threat-blocked domain", response)

        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        assertEquals("QR bit should be set", 0x81.toByte(), responseBytes[2])
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals("ANCOUNT must be 0 for NXDOMAIN", 0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `threat block wins over ad blocklist and category when domain is in all`() {
        // A phishing domain that also happens to be on the ad list / a category
        // must still be killed with NXDOMAIN, never sinkholed to 0.0.0.0.
        val interceptor = makeInterceptor(
            isThreatBlocked = { domain -> domain == "phish.example" },
            isCategoryBlocked = { domain -> domain == "phish.example" },
            isAdBlocked = { domain -> domain == "phish.example" }
        )

        val query = createDnsQuery("phish.example")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNotNull(response)
        val responseBytes = ByteArray(response!!.remaining())
        response.get(responseBytes)

        // NXDOMAIN (threat), not 0.0.0.0 with ANCOUNT=1 (ad block)
        assertEquals("RCODE should be NXDOMAIN (3)", 0x83.toByte(), responseBytes[3])
        assertEquals(0x00.toByte(), responseBytes[6])
        assertEquals(0x00.toByte(), responseBytes[7])
    }

    @Test
    fun `DnsPolicy resolves BlockNxdomain for threat hit`() {
        val verdict = DnsPolicy.resolve(
            domain = "malware.example", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { true },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.BlockNxdomain, verdict)
    }

    @Test
    fun `DnsPolicy threat hit wins over ad sinkhole`() {
        val verdict = DnsPolicy.resolve(
            domain = "both.example", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { true },
            isCategoryBlocked = { false },
            isAdBlocked = { true }
        )
        // Threat is checked before the ad list, so the verdict is NXDOMAIN.
        assertEquals(Verdict.BlockNxdomain, verdict)
    }

    @Test
    fun `redirect wins over threat block`() {
        // SafeSearch/YouTube redirect precedes the threat check, mirroring the
        // documented ordering (redirect > threat > category > ad).
        val verdict = DnsPolicy.resolve(
            domain = "www.youtube.com", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> byteArrayOf(216.toByte(), 239.toByte(), 38, 120) },
            isThreatBlocked = { true },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertTrue("Redirect must win over a threat hit", verdict is Verdict.Redirect)
    }

    @Test
    fun `non-listed domain still resolves Allow with threat check wired`() {
        val verdict = DnsPolicy.resolve(
            domain = "example.com", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { false },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `DnsPolicy resolves Allow when nothing matches`() {
        val verdict = DnsPolicy.resolve(
            domain = "example.com", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { false },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `DnsPolicy resolves Nodata for empty redirect IP`() {
        val verdict = DnsPolicy.resolve(
            domain = "google.com", queryType = 28,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> byteArrayOf() },
            isThreatBlocked = { true },
            isCategoryBlocked = { true },
            isAdBlocked = { true }
        )
        assertEquals(Verdict.Nodata, verdict)
    }

    // --- Domain allowlist (false-positive escape hatch) ---

    @Test
    fun `allowlisted domain resolves Allow even when threat, category and ad listed`() {
        // The allowlist is the very first check: a parent-rescued domain must win
        // over every blocking rule, including the always-on threat list.
        val verdict = DnsPolicy.resolve(
            domain = "rescued.example", queryType = 1,
            isDomainAllowed = { domain -> domain == "rescued.example" },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { true },
            isCategoryBlocked = { true },
            isAdBlocked = { true }
        )
        assertEquals("allowlist must win over threat/category/ad", Verdict.Allow, verdict)
    }

    @Test
    fun `allowlist wins over a SafeSearch redirect`() {
        // Allowlisting precedes the redirect — the parent explicitly chose to let
        // this domain through untouched.
        val verdict = DnsPolicy.resolve(
            domain = "google.com", queryType = 1,
            isDomainAllowed = { true },
            getRedirectIp = { _, _ -> byteArrayOf(216.toByte(), 239.toByte(), 38, 120) },
            isThreatBlocked = { false },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `non-allowlisted domain still blocks normally`() {
        val verdict = DnsPolicy.resolve(
            domain = "malware.example", queryType = 1,
            isDomainAllowed = { domain -> domain == "rescued.example" },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { true },
            isCategoryBlocked = { false },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.BlockNxdomain, verdict)
    }

    @Test
    fun `processDnsPacket returns null for an allowlisted domain that is also blocked`() {
        // End-to-end through the interceptor: allowlisted -> forwarded upstream
        // (null response), never sinkholed.
        val interceptor = makeInterceptor(
            isDomainAllowed = { domain -> domain == "health.example" },
            isThreatBlocked = { true },
            isAdBlocked = { true }
        )

        val query = createDnsQuery("health.example")
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNull("Allowlisted domain must not be answered/blocked", response)
    }

    // --- NSFW category gating (Adult content toggle) ---

    @Test
    fun `nsfw domain is allowed when the nsfw category is disabled`() {
        // The Adult content toggle is OFF by default — nothing extra is blocked.
        val verdict = DnsPolicy.resolve(
            domain = "porn.example", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { false },
            isCategoryBlocked = { false }, // nsfw category not enabled
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `nsfw domain gets NXDOMAIN when the nsfw category is enabled`() {
        // With the Adult content toggle ON the nsfw set feeds isCategoryBlocked.
        val verdict = DnsPolicy.resolve(
            domain = "porn.example", queryType = 1,
            isDomainAllowed = { false },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { false },
            isCategoryBlocked = { domain -> domain == "porn.example" },
            isAdBlocked = { false }
        )
        assertEquals(Verdict.BlockNxdomain, verdict)
    }

    @Test
    fun `allowlist rescues a falsely-blocked nsfw domain`() {
        // Critical for NSFW over-block: a health/education site caught by the nsfw
        // list is rescued by the parent allowlist even with the category ON.
        val verdict = DnsPolicy.resolve(
            domain = "sexeducation.example", queryType = 1,
            isDomainAllowed = { domain -> domain == "sexeducation.example" },
            getRedirectIp = { _, _ -> null },
            isThreatBlocked = { false },
            isCategoryBlocked = { true }, // nsfw list over-blocks it
            isAdBlocked = { false }
        )
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `processDnsPacket returns null for a QNAME longer than 253 bytes`() {
        // A crafted multi-KB name must never reach the matcher: the parser caps
        // the assembled name at the DNS max (253) and falls through to forward.
        // Track every label that would otherwise be reported as blocked so we can
        // assert the matcher was never consulted with the oversized name.
        val seen = mutableListOf<String>()
        val interceptor = makeInterceptor(isAdBlocked = { domain ->
            seen.add(domain)
            false
        })

        // 5 labels of 63 'a's joined by dots = 5*63 + 4 = 319 bytes — over 253.
        // (Stays within createDnsQuery's 512-byte builder.)
        val label = "a".repeat(63)
        val oversized = List(5) { label }.joinToString(".")

        val query = createDnsQuery(oversized)
        val response = interceptor.processDnsPacket(ByteBuffer.wrap(query))

        assertNull("Oversized QNAME must be forwarded (null), not matched", response)
        assertTrue("Matcher must not be consulted with an oversized name", seen.isEmpty())
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
