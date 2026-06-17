package com.calyptra.app.vpn.dot

import java.io.InputStream

/**
 * Wire framing for DNS over a stream transport (TCP/TLS), per RFC 7858 §3.3 /
 * RFC 1035 §4.2.2: a DNS message is prefixed with a 2-byte big-endian length so
 * the receiver knows where one message ends on a stream that has no datagram
 * boundaries (unlike UDP).
 *
 * Pure and stateless — the only I/O dependency is [java.io.InputStream], so this
 * is unit-testable with a [java.io.ByteArrayInputStream] and needs no sockets or
 * the Android VPN stack.
 */
internal object DotFraming {

    /** A DNS message length is a 16-bit field, so it cannot exceed this. */
    const val MAX_MESSAGE_LEN = 0xFFFF

    /**
     * Prefixes [message] with its 2-byte big-endian length, ready to write to a
     * DoT connection. Returns null if [message] is empty or too large to frame
     * (the length field is only 16 bits) — such a message must never be sent.
     */
    fun frame(message: ByteArray): ByteArray? {
        if (message.isEmpty() || message.size > MAX_MESSAGE_LEN) return null
        val out = ByteArray(message.size + 2)
        out[0] = ((message.size ushr 8) and 0xFF).toByte()
        out[1] = (message.size and 0xFF).toByte()
        System.arraycopy(message, 0, out, 2, message.size)
        return out
    }

    /**
     * Reads exactly one length-prefixed DNS message from [input]. The 2-byte
     * length is read first, then that many payload bytes — looping over [read]
     * because a TLS stream may hand back fewer bytes than requested per call.
     *
     * Returns null on a clean EOF before/within the message, a zero-length
     * prefix (no valid DNS message is empty), or truncation (the stream ended
     * before the declared payload arrived). Never returns a partial message.
     */
    fun readMessage(input: InputStream): ByteArray? {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) return null // EOF inside the length prefix
        val length = (hi shl 8) or lo
        if (length == 0) return null

        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) return null // truncated before the full payload arrived
            read += n
        }
        return buf
    }
}
