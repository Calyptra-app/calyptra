package com.calyptra.app.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BlocklistParserTest {

    private val roomy = BlocklistParser.Limits(maxBytes = 1_000_000, maxLineLength = 256, maxDomains = 1_000)

    private fun parse(text: String, limits: BlocklistParser.Limits = roomy): Set<String> =
        BlocklistParser.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), limits)

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected BlocklistLimitException")
        } catch (_: BlocklistLimitException) {
        }
    }

    @Test
    fun `normal list keeps existing parsing semantics`() {
        val text = """
            # Title: HaGeZi's Light DNS Blocklist
            # Number of entries: 3

            ads.example.com
              Tracker.Example.NET
            ads.example.com
            #commented.example
        """.trimIndent()

        assertEquals(setOf("ads.example.com", "tracker.example.net"), parse(text))
    }

    @Test
    fun `crlf and cr line endings and missing trailing newline are handled`() {
        assertEquals(setOf("a.com", "b.com", "c.com", "d.com"), parse("a.com\r\nb.com\rc.com\nd.com"))
    }

    @Test
    fun `byte cap exceeded is rejected`() {
        val text = (1..100).joinToString("\n") { "d$it.example.com" }
        assertRejected { parse(text, roomy.copy(maxBytes = 200)) }
    }

    @Test
    fun `byte cap stops reading as soon as it is exceeded`() {
        // An endless stream of short valid lines: without a streaming byte cap
        // this would never terminate (or would exhaust memory).
        var served = 0L
        val endless = object : InputStream() {
            private val line = "x.example.com\n".toByteArray()
            override fun read(): Int = line[(served++ % line.size).toInt()].toInt()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = read().toByte()
                return len
            }
        }
        val limits = roomy.copy(maxBytes = 64 * 1024, maxDomains = Int.MAX_VALUE)
        assertRejected { BlocklistParser.parse(endless, limits) }
        // At most one read chunk past the cap was pulled from the source.
        assertTrue("read $served bytes", served <= limits.maxBytes + 64 * 1024)
    }

    @Test
    fun `list exactly at the byte cap is accepted`() {
        val text = "a.com\nb.com\n"
        assertEquals(setOf("a.com", "b.com"), parse(text, roomy.copy(maxBytes = text.length.toLong())))
    }

    @Test
    fun `line too long is rejected`() {
        val text = "ok.example.com\n" + "a".repeat(300) + ".com\n"
        assertRejected { parse(text) }
    }

    @Test
    fun `over-long comment line is also rejected`() {
        assertRejected { parse("#" + "x".repeat(1_000) + "\na.com\n") }
    }

    @Test
    fun `huge single line without newline is rejected before buffering it`() {
        val limits = roomy.copy(maxBytes = Long.MAX_VALUE)
        var served = 0L
        val noNewlines = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len); served += len; return len
            }
        }
        assertRejected { BlocklistParser.parse(noNewlines, limits) }
        assertTrue("read $served bytes", served < 1_000_000)
    }

    @Test
    fun `line at the max length is accepted`() {
        val domain = "a".repeat(252) + ".com" // 256 chars
        assertEquals(setOf(domain), parse("$domain\n"))
    }

    @Test
    fun `domain cap exceeded is rejected`() {
        val text = (1..11).joinToString("\n") { "d$it.example.com" }
        assertRejected { parse(text, roomy.copy(maxDomains = 10)) }
    }

    @Test
    fun `duplicates do not count toward the domain cap`() {
        val text = (1..10).joinToString("\n") { "d$it.example.com" } + "\nd1.example.com\nD2.EXAMPLE.COM\n"
        assertEquals(10, parse(text, roomy.copy(maxDomains = 10)).size)
    }

    @Test
    fun `production limits accept a realistically sized list`() {
        val count = 120_000
        val text = buildString {
            append("# Title: test feed\n")
            for (i in 0 until count) append("host").append(i).append(".tracker-example.com\n")
        }
        assertEquals(count, parse(text, BlocklistParser.AD_LIMITS).size)
        assertEquals(count, parse(text, BlocklistParser.THREAT_LIMITS).size)
    }
}
