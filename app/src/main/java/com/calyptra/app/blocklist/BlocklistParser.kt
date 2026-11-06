package com.calyptra.app.blocklist

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/** Thrown when a downloaded list breaks one of the [BlocklistParser.Limits]. */
class BlocklistLimitException(message: String) : IOException(message)

/**
 * Bounded, streaming parser for downloaded blocklists.
 *
 * The response is consumed line by line and every limit is enforced *while*
 * reading: the byte cap trips on the first byte past the limit, the line cap
 * trips before an over-long line is buffered, and the domain cap trips on the
 * first domain past the limit. Any breach throws [BlocklistLimitException] and
 * the caller gets nothing, so an oversized or hostile response can never be
 * partially applied (all-or-nothing) and can never exhaust memory.
 *
 * Parsing semantics are unchanged from the previous `useLines` implementation:
 * blank lines and lines starting with `#` are skipped, everything else is
 * trimmed and lowercased, duplicates collapse into the set. Line terminators
 * are `\n`, `\r` and `\r\n`, as in [java.io.BufferedReader.readLine].
 *
 * Pure (no Android dependencies) so it can be unit-tested on the JVM.
 */
object BlocklistParser {

    data class Limits(
        /** Maximum raw response size in bytes. */
        val maxBytes: Long,
        /** Maximum characters in a single line (terminator excluded). */
        val maxLineLength: Int,
        /** Maximum number of distinct domains accepted. */
        val maxDomains: Int,
    )

    /**
     * Longest line we accept. A DNS name is at most 253 characters; the rest is
     * headroom for list header comments (HaGeZi headers are well under 200
     * characters) and stray whitespace. Anything longer is not a domain list.
     */
    const val MAX_LINE_LENGTH = 1_024

    /**
     * Upper bound on raw bytes for one list. Generous on purpose: at the largest
     * domain cap (250k) this still leaves ~67 bytes per entry, far above the
     * ~20-25 byte average of a real domain line plus its newline, and covers the
     * comment header. Current upstream lists are low single-digit megabytes, so a
     * legitimate list hitting this would already be outside the domain cap.
     */
    const val MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

    /** Limits for the HaGeZi Light ad/tracker list. */
    val AD_LIMITS = Limits(
        maxBytes = MAX_RESPONSE_BYTES,
        maxLineLength = MAX_LINE_LENGTH,
        maxDomains = BlocklistManager.MAX_REMOTE_DOMAINS,
    )

    /** Limits for the HaGeZi TIF Mini threat list. */
    val THREAT_LIMITS = Limits(
        maxBytes = MAX_RESPONSE_BYTES,
        maxLineLength = MAX_LINE_LENGTH,
        maxDomains = BlocklistManager.MAX_THREAT_DOMAINS,
    )

    /** Parses a UTF-8 [input], enforcing the byte cap on the raw stream. */
    fun parse(input: InputStream, limits: Limits): Set<String> {
        val bounded = ByteLimitedInputStream(input, limits.maxBytes)
        return parse(InputStreamReader(bounded, Charsets.UTF_8), limits)
    }

    /**
     * Parses already-decoded text from [reader]. The byte cap is not applied here
     * (there are no bytes); use the [InputStream] overload for network input.
     */
    fun parse(reader: Reader, limits: Limits): Set<String> {
        val domains = HashSet<String>()
        val line = StringBuilder()
        val buf = CharArray(8 * 1024)
        var pendingCr = false

        fun endLine() {
            val text = line.toString()
            line.setLength(0)
            if (text.isBlank() || text.startsWith("#")) return
            domains.add(text.trim().lowercase())
            if (domains.size > limits.maxDomains) {
                throw BlocklistLimitException("Blocklist exceeds ${limits.maxDomains} domains")
            }
        }

        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (pendingCr) {
                    pendingCr = false
                    if (c == '\n') continue // \r\n is one terminator
                }
                when (c) {
                    '\n' -> endLine()
                    '\r' -> { endLine(); pendingCr = true }
                    else -> {
                        if (line.length >= limits.maxLineLength) {
                            throw BlocklistLimitException("Blocklist line exceeds ${limits.maxLineLength} characters")
                        }
                        line.append(c)
                    }
                }
            }
        }
        if (line.isNotEmpty()) endLine()
        return domains
    }

    /** Throws as soon as more than [maxBytes] bytes have been read. */
    private class ByteLimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        private fun account(n: Long) {
            if (n <= 0) return
            count += n
            if (count > maxBytes) {
                throw BlocklistLimitException("Blocklist response exceeds $maxBytes bytes")
            }
        }

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) account(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            account(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            account(skipped)
            return skipped
        }

        override fun markSupported(): Boolean = false
    }
}
