package com.calyptra.app.blocklist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    @Test
    fun `isBlocked returns true for exact match`() {
        val blocklist = setOf("ads.google.com", "unityads.unity3d.com")
        val matcher = DomainMatcher(blocklist)

        assertTrue(matcher.isBlocked("ads.google.com"))
        assertTrue(matcher.isBlocked("unityads.unity3d.com"))
    }

    @Test
    fun `isBlocked returns true for subdomain match`() {
        val blocklist = setOf("doubleclick.net")
        val matcher = DomainMatcher(blocklist)

        assertTrue(matcher.isBlocked("g.doubleclick.net"))
        assertTrue(matcher.isBlocked("ads.g.doubleclick.net"))
    }

    @Test
    fun `isBlocked returns false for allowed domains`() {
        val blocklist = setOf("ads.google.com")
        val matcher = DomainMatcher(blocklist)

        assertFalse(matcher.isBlocked("google.com"))
        assertFalse(matcher.isBlocked("example.com"))
    }
    
    @Test
    fun `isBlocked handles case insensitivity`() {
        val blocklist = setOf("ads.google.com")
        val matcher = DomainMatcher(blocklist)

        assertTrue(matcher.isBlocked("ADS.GOOGLE.COM"))
    }
}
