package com.calyptra.app.blocklist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBlockManagerTest {

    private val fakeSets = mapOf(
        SocialCategory.TIKTOK to setOf("tiktok.com", "tiktokcdn.com"),
        SocialCategory.INSTAGRAM to setOf("instagram.com", "cdninstagram.com")
    )

    private fun manager() = CategoryBlockManager(loadCategory = { category ->
        fakeSets[category] ?: emptySet()
    })

    @Test
    fun `blocks nothing when no categories are enabled`() = runBlocking {
        val manager = manager()
        manager.setEnabledCategories(emptySet())
        assertFalse(manager.isCategoryBlocked("tiktok.com"))
        assertFalse(manager.isCategoryBlocked("instagram.com"))
    }

    @Test
    fun `blocks nothing before initialization`() {
        assertFalse(manager().isCategoryBlocked("tiktok.com"))
    }

    @Test
    fun `enabled category blocks its apex domain and subdomains`() = runBlocking {
        val manager = manager()
        manager.setEnabledCategories(setOf(SocialCategory.TIKTOK))
        assertTrue(manager.isCategoryBlocked("tiktok.com"))
        assertTrue("subdomains must match", manager.isCategoryBlocked("api.tiktok.com"))
        assertTrue(manager.isCategoryBlocked("v16-webapp.tiktokcdn.com"))
    }

    @Test
    fun `other categories stay unblocked`() = runBlocking {
        val manager = manager()
        manager.setEnabledCategories(setOf(SocialCategory.TIKTOK))
        assertFalse(manager.isCategoryBlocked("instagram.com"))
        assertFalse(manager.isCategoryBlocked("example.com"))
    }

    @Test
    fun `disabling a category stops blocking without recreating the manager`() = runBlocking {
        val manager = manager()
        manager.setEnabledCategories(setOf(SocialCategory.TIKTOK, SocialCategory.INSTAGRAM))
        assertTrue(manager.isCategoryBlocked("instagram.com"))

        manager.setEnabledCategories(setOf(SocialCategory.TIKTOK))
        assertFalse(manager.isCategoryBlocked("instagram.com"))
        assertTrue("remaining category keeps blocking", manager.isCategoryBlocked("tiktok.com"))
    }

    @Test
    fun `does not block lookalike domains`() = runBlocking {
        val manager = manager()
        manager.setEnabledCategories(setOf(SocialCategory.TIKTOK))
        assertFalse("suffix-lookalike must not match", manager.isCategoryBlocked("nottiktok.com"))
    }
}
