package com.calyptra.app.safesearch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SafeSearchManagerTest {

    private lateinit var manager: SafeSearchManager

    @Before
    fun setUp() {
        manager = SafeSearchManager()
    }

    @Test
    fun `getRedirectIp returns IP for google_com when enabled`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("google.com", 1)
        assertNotNull("Should return redirect IP for google.com", result)
        assertEquals("Should return 4-byte IPv4 address", 4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns null for google_com when disabled`() {
        manager.safeSearchEnabled = false
        val result = manager.getRedirectIp("google.com", 1)
        assertNull("Should return null when SafeSearch is disabled", result)
    }

    @Test
    fun `getRedirectIp handles www prefix for Google domains`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("www.google.com", 1)
        assertNotNull("Should return redirect IP for www.google.com", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp matches Google country domains`() {
        manager.safeSearchEnabled = true

        val countryDomains = listOf("google.co.uk", "google.de", "google.fr", "google.co.jp", "google.com.au")
        for (domain in countryDomains) {
            val result = manager.getRedirectIp(domain, 1)
            assertNotNull("Should redirect $domain", result)
            assertEquals("$domain should return 4-byte IP", 4, result!!.size)
        }
    }

    @Test
    fun `getRedirectIp matches www prefix on Google country domains`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("www.google.co.uk", 1)
        assertNotNull("Should redirect www.google.co.uk", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns IP for bing_com when enabled`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("bing.com", 1)
        assertNotNull("Should return redirect IP for bing.com", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns IP for www_bing_com when enabled`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("www.bing.com", 1)
        assertNotNull("Should return redirect IP for www.bing.com", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns YouTube strict IP when level is strict`() {
        manager.youtubeRestrictLevel = "strict"
        val result = manager.getRedirectIp("youtube.com", 1)
        assertNotNull("Should return redirect IP for youtube.com", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns YouTube moderate IP when level is moderate`() {
        manager.youtubeRestrictLevel = "moderate"
        val result = manager.getRedirectIp("youtube.com", 1)
        assertNotNull("Should return redirect IP for youtube.com in moderate mode", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `getRedirectIp returns null for YouTube when level is off`() {
        manager.youtubeRestrictLevel = "off"
        val result = manager.getRedirectIp("youtube.com", 1)
        assertNull("Should return null when YouTube restriction is off", result)
    }

    @Test
    fun `getRedirectIp redirects YouTube subdomains`() {
        manager.youtubeRestrictLevel = "strict"
        val domains = listOf("www.youtube.com", "m.youtube.com", "music.youtube.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "www.youtube-nocookie.com")
        for (domain in domains) {
            val result = manager.getRedirectIp(domain, 1)
            assertNotNull("Should redirect $domain", result)
            assertEquals("$domain should return 4-byte IP", 4, result!!.size)
        }
    }

    @Test
    fun `getRedirectIp returns empty array for AAAA query on redirected domain`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("google.com", 28) // AAAA
        assertNotNull("Should return non-null for AAAA on redirected domain", result)
        assertEquals("Should return empty array for AAAA query", 0, result!!.size)
    }

    @Test
    fun `getRedirectIp returns empty array for AAAA query on YouTube domain`() {
        manager.youtubeRestrictLevel = "strict"
        val result = manager.getRedirectIp("youtube.com", 28) // AAAA
        assertNotNull(result)
        assertEquals("Should return empty array for AAAA YouTube query", 0, result!!.size)
    }

    @Test
    fun `getRedirectIp returns null for non-search domain`() {
        val result = manager.getRedirectIp("example.com", 1)
        assertNull("Should return null for non-search domain", result)
    }

    @Test
    fun `getRedirectIp is case insensitive`() {
        manager.safeSearchEnabled = true
        val result = manager.getRedirectIp("GOOGLE.COM", 1)
        assertNotNull("Should handle uppercase domain", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `YouTube strict and moderate return different IPs`() {
        manager.youtubeRestrictLevel = "strict"
        val strictIp = manager.getRedirectIp("youtube.com", 1)!!

        manager.youtubeRestrictLevel = "moderate"
        val moderateIp = manager.getRedirectIp("youtube.com", 1)!!

        // They should be different IPs (different last octet).
        val different = !strictIp.contentEquals(moderateIp)
        assertEquals("Strict and moderate should return different IPs", true, different)
    }
}
