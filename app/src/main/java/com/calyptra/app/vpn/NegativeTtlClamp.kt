package com.calyptra.app.vpn

/**
 * Caps how long a negative upstream answer (NXDOMAIN, or NOERROR with no
 * answers) may be cached on the device.
 *
 * Resolvers cache a negative answer for min(SOA TTL, SOA MINIMUM) of the SOA
 * record in the authority section (RFC 2308). CleanBrowsing's block answers
 * carry 3600 s, so after a parent allowlists a site, Android and browsers kept
 * the old "no such site" for up to an hour. Clamping both fields to
 * [MAX_NEGATIVE_TTL_S] makes allowlist changes take effect within a minute.
 * Positive answers are left untouched.
 *
 * Works in place on a copy; any malformed or unexpected message is returned
 * unchanged (never worse than forwarding it as-is).
 */
object NegativeTtlClamp {

    const val MAX_NEGATIVE_TTL_S = 60L

    private const val TYPE_SOA = 6
    private const val RCODE_NXDOMAIN = 3

    fun clamp(message: ByteArray, maxTtl: Long = MAX_NEGATIVE_TTL_S): ByteArray {
        if (message.size < 12) return message
        val rcode = message[3].toInt() and 0x0F
        val qd = u16(message, 4)
        val an = u16(message, 6)
        val ns = u16(message, 8)
        val negative = rcode == RCODE_NXDOMAIN || (rcode == 0 && an == 0)
        if (!negative || ns == 0) return message

        val out = message.copyOf()
        try {
            var off = 12
            repeat(qd) {
                off = skipName(out, off)
                off += 4 // QTYPE + QCLASS
            }
            repeat(an) { off = skipRecord(out, off) }
            repeat(ns) {
                off = skipName(out, off)
                if (off + 10 > out.size) return message
                val type = u16(out, off)
                val ttlOff = off + 4
                val rdLen = u16(out, off + 8)
                val rdata = off + 10
                if (rdata + rdLen > out.size) return message
                if (type == TYPE_SOA) {
                    clampU32(out, ttlOff, maxTtl)
                    // SOA RDATA ends with SERIAL REFRESH RETRY EXPIRE MINIMUM;
                    // MINIMUM is the last 4 bytes.
                    if (rdLen >= 20) clampU32(out, rdata + rdLen - 4, maxTtl)
                }
                off = rdata + rdLen
            }
        } catch (e: IndexOutOfBoundsException) {
            return message
        }
        return out
    }

    private fun skipRecord(b: ByteArray, start: Int): Int {
        val off = skipName(b, start)
        if (off + 10 > b.size) throw IndexOutOfBoundsException()
        return off + 10 + u16(b, off + 8)
    }

    /** Skips a (possibly compressed) domain name; returns the offset after it. */
    private fun skipName(b: ByteArray, start: Int): Int {
        var off = start
        while (true) {
            if (off >= b.size) throw IndexOutOfBoundsException()
            val len = b[off].toInt() and 0xFF
            when {
                len == 0 -> return off + 1
                len and 0xC0 == 0xC0 -> return off + 2 // pointer ends the name
                else -> off += 1 + len
            }
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun clampU32(b: ByteArray, off: Int, max: Long) {
        val v = ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
        if (v <= max) return
        b[off] = (max ushr 24).toByte()
        b[off + 1] = (max ushr 16).toByte()
        b[off + 2] = (max ushr 8).toByte()
        b[off + 3] = max.toByte()
    }
}
