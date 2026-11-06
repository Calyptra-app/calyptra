package com.calyptra.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NegativeTtlClampTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun u32(b: ByteArray, off: Int) =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    /** Real CleanBrowsing Family answer for cinobo.com (2026-11-06): NXDOMAIN,
     *  SOA cleanbrowsing.rpz.noc.org, TTL 3600, MINIMUM 86400. */
    private val cleanBrowsingBlock = hex(
        "1234818300010000000100000663696e6f626f03636f6d0000010001c00c0006000100000e10" +
            "003e0d636c65616e62726f7773696e670372707a036e6f63036f7267000c616363657373706f" +
            "6c696379c0360000000100001c20000003840012750000015180"
    )

    /** Real positive answer for example.com: two A records, TTL 41. */
    private val positive = hex(
        "123481800001000200000000076578616d706c6503636f6d0000010001c00c00010001000000" +
            "290004ac4293f3c00c000100010000002900046814179a"
    )

    @Test
    fun `CleanBrowsing block is capped to 60 s in both SOA TTL and MINIMUM`() {
        val out = NegativeTtlClamp.clamp(cleanBrowsingBlock)
        val soaTtlOff = 28 + 2 + 4 // question ends at 28; owner pointer, type+class
        assertEquals(3600L, u32(cleanBrowsingBlock, soaTtlOff))
        assertEquals(60L, u32(out, soaTtlOff))
        assertEquals(60L, u32(out, out.size - 4))
        // Nothing else changes.
        assertEquals(cleanBrowsingBlock.size, out.size)
        assertArrayEquals(cleanBrowsingBlock.copyOfRange(0, soaTtlOff), out.copyOfRange(0, soaTtlOff))
    }

    @Test
    fun `positive answers are untouched`() {
        assertArrayEquals(positive, NegativeTtlClamp.clamp(positive))
    }

    @Test
    fun `short negative TTLs are kept as they are`() {
        val once = NegativeTtlClamp.clamp(cleanBrowsingBlock, maxTtl = 60)
        assertArrayEquals(once, NegativeTtlClamp.clamp(once, maxTtl = 60))
    }

    @Test
    fun `truncated or malformed messages are returned unchanged`() {
        for (cut in listOf(5, 20, 40, cleanBrowsingBlock.size - 3)) {
            val broken = cleanBrowsingBlock.copyOf(cut)
            assertArrayEquals(broken, NegativeTtlClamp.clamp(broken))
        }
    }
}
