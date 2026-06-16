package com.calyptra.app.blocklist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBlockManagerTest {

    private val fakeSets = mapOf(
        SocialCategory.TIKTOK to setOf("tiktok.com", "tiktokcdn.com"),
        SocialCategory.INSTAGRAM to setOf("instagram.com", "cdninstagram.com"),
        SocialCategory.REDDIT to setOf("reddit.com", "redd.it", "redditcdn.com"),
        SocialCategory.NSFW to setOf("badporn.example", "adult.example")
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

    @Test
    fun `removing reddit while nsfw stays enabled stops blocking reddit`() = runBlocking {
        // Direct reproduction of the live-update bug: enable {REDDIT, NSFW} then
        // set {NSFW}. Reddit (incl. its cache-proof apex redditcdn.com) must go
        // unblocked while a known NSFW domain stays blocked.
        val manager = manager()
        manager.setEnabledCategories(setOf(SocialCategory.REDDIT, SocialCategory.NSFW))
        assertTrue(manager.isCategoryBlocked("reddit.com"))
        assertTrue(manager.isCategoryBlocked("redditcdn.com"))

        manager.setEnabledCategories(setOf(SocialCategory.NSFW))
        assertFalse("reddit.com must unblock after removal", manager.isCategoryBlocked("reddit.com"))
        assertFalse("redd.it must unblock after removal", manager.isCategoryBlocked("redd.it"))
        assertFalse("redditcdn.com must unblock after removal", manager.isCategoryBlocked("redditcdn.com"))
        assertTrue("nsfw stays blocked", manager.isCategoryBlocked("badporn.example"))
    }

    @Test
    fun `a slow older rebuild cannot overwrite a newer one`() = runBlocking {
        // Models the race: an add ({REDDIT, NSFW}) whose NSFW load is slow is
        // issued just before a removal ({NSFW}). The older, slower rebuild must
        // NOT win — the final matcher must reflect the newer {NSFW} set, so
        // reddit ends up unblocked.
        val gate = CompletableDeferred<Unit>()
        val manager = CategoryBlockManager(loadCategory = { category ->
            // Make ONLY the first (older) NSFW load block until released, so the
            // newer request finishes first and the older one resumes afterwards.
            if (category == SocialCategory.NSFW && !gate.isCompleted) {
                gate.await()
            }
            fakeSets[category] ?: emptySet()
        })

        // Older request: {REDDIT, NSFW} — its NSFW load parks on the gate.
        val older = async(Dispatchers.Unconfined) {
            manager.setEnabledCategories(setOf(SocialCategory.REDDIT, SocialCategory.NSFW))
        }
        // Newer request: {NSFW} — claims a higher token. With the mutex it waits
        // for the older one to release, but the token guard makes the older one
        // discard its stale result.
        val newer = launch(Dispatchers.Unconfined) {
            manager.setEnabledCategories(setOf(SocialCategory.NSFW))
        }

        // Let the older rebuild proceed; whichever order they finish in, the
        // newest token must win.
        gate.complete(Unit)
        older.await()
        newer.join()

        assertFalse("stale {REDDIT,NSFW} rebuild must not win", manager.isCategoryBlocked("reddit.com"))
        assertFalse(manager.isCategoryBlocked("redditcdn.com"))
        assertTrue("newer {NSFW} set must be live", manager.isCategoryBlocked("badporn.example"))
    }
}
