package com.calyptra.app.blocklist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the HaGeZi paths: the old `domains/` tree 404s, which made list
 *  updates fail silently and left devices on the small bundled list. */
class BlocklistUrlsTest {

    @Test
    fun `both lists use the current wildcard onlydomains exports`() {
        for (url in listOf(BlocklistUpdater.AD_LIST_URL, BlocklistUpdater.THREAT_LIST_URL)) {
            assertTrue(url, url.startsWith("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"))
            assertTrue(url, url.endsWith("-onlydomains.txt"))
            assertFalse("removed upstream: $url", url.contains("/domains/"))
        }
    }
}
