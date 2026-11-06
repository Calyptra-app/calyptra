package com.calyptra.app.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class BlocklistCacheStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun domains(n: Int, prefix: String = "d") = (1..n).map { "$prefix$it.example.com" }.toSet()

    private fun cacheWith(content: Set<String>): File {
        val file = File(tmp.root, "updated_blocklist.txt")
        BlocklistCacheStore.writeAtomically(file, content)
        return file
    }

    @Test
    fun `acceptable update replaces the cache and leaves no temp file`() {
        val file = cacheWith(domains(2_000, "old"))
        val update = domains(3_000, "new")

        assertTrue(BlocklistCacheStore.replaceIfAcceptable(file, update, maxDomains = 10_000))
        assertEquals(update, BlocklistCacheStore.read(file, 10_000))
        assertFalse(File(tmp.root, "updated_blocklist.txt.tmp").exists())
    }

    @Test
    fun `oversized update is rejected outright and the previous cache is kept`() {
        val previous = domains(2_000, "old")
        val file = cacheWith(previous)
        val before = file.readBytes()

        assertFalse(BlocklistCacheStore.replaceIfAcceptable(file, domains(5_001), maxDomains = 5_000))
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(previous, BlocklistCacheStore.read(file, 5_000))
    }

    @Test
    fun `implausibly small update is rejected and the previous cache is kept`() {
        val file = cacheWith(domains(4_000, "old"))
        val before = file.readBytes()

        assertFalse(BlocklistCacheStore.replaceIfAcceptable(file, domains(1_500), maxDomains = 10_000))
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `rejected download never reaches the cache`() {
        // The real flow: parse the response, then save. A response over the
        // limits throws during parsing, so the cache is never touched.
        val previous = domains(2_000, "old")
        val file = cacheWith(previous)
        val before = file.readBytes()
        val limits = BlocklistParser.Limits(maxBytes = 1_000_000, maxLineLength = 1_024, maxDomains = 3_000)
        val hostile = domains(3_001, "new").joinToString("\n")

        val result = runCatching {
            val parsed = BlocklistParser.parse(ByteArrayInputStream(hostile.toByteArray()), limits)
            BlocklistCacheStore.replaceIfAcceptable(file, parsed, limits.maxDomains)
        }

        assertTrue(result.exceptionOrNull() is BlocklistLimitException)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(previous, BlocklistCacheStore.read(file, 10_000))
    }

    @Test
    fun `missing cache reads as empty`() {
        assertEquals(emptySet<String>(), BlocklistCacheStore.read(File(tmp.root, "absent.txt"), 10))
    }
}
