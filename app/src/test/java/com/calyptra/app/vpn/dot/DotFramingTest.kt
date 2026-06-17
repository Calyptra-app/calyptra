package com.calyptra.app.vpn.dot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class DotFramingTest {

    /** An InputStream that hands back at most [chunk] bytes per read call, to
     *  exercise the short-read loop a real TLS stream forces on the reader. */
    private class ChunkedStream(data: ByteArray, private val chunk: Int) : InputStream() {
        private val backing = ByteArrayInputStream(data)
        override fun read(): Int = backing.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            backing.read(b, off, minOf(len, chunk))
    }

    @Test
    fun `frame prepends the 2-byte big-endian length`() {
        val msg = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val framed = DotFraming.frame(msg)!!
        assertEquals(5, framed.size)
        assertEquals(0x00, framed[0].toInt() and 0xFF) // length hi
        assertEquals(0x03, framed[1].toInt() and 0xFF) // length lo = 3
        assertArrayEquals(msg, framed.copyOfRange(2, framed.size))
    }

    @Test
    fun `frame length uses both bytes for values over 255`() {
        val msg = ByteArray(300) { 0x7F }
        val framed = DotFraming.frame(msg)!!
        assertEquals(0x01, framed[0].toInt() and 0xFF) // 300 = 0x012C
        assertEquals(0x2C, framed[1].toInt() and 0xFF)
        assertEquals(302, framed.size)
    }

    @Test
    fun `frame then readMessage round-trips`() {
        val msg = "a-dns-message".toByteArray(Charsets.US_ASCII)
        val framed = DotFraming.frame(msg)!!
        val decoded = DotFraming.readMessage(ByteArrayInputStream(framed))
        assertArrayEquals(msg, decoded)
    }

    @Test
    fun `readMessage reassembles a payload delivered one byte at a time`() {
        val msg = ByteArray(500) { (it and 0xFF).toByte() }
        val framed = DotFraming.frame(msg)!!
        val decoded = DotFraming.readMessage(ChunkedStream(framed, chunk = 1))
        assertArrayEquals(msg, decoded)
    }

    @Test
    fun `readMessage returns null on empty stream`() {
        assertNull(DotFraming.readMessage(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `readMessage returns null when only one length byte is available`() {
        assertNull(DotFraming.readMessage(ByteArrayInputStream(byteArrayOf(0x00))))
    }

    @Test
    fun `readMessage returns null on a zero-length prefix`() {
        // No valid DNS message is empty; a 0x0000 length is malformed.
        assertNull(DotFraming.readMessage(ByteArrayInputStream(byteArrayOf(0x00, 0x00))))
    }

    @Test
    fun `readMessage returns null when the payload is truncated`() {
        // Declares 4 bytes but only 2 follow.
        val truncated = byteArrayOf(0x00, 0x04, 0x01, 0x02)
        assertNull(DotFraming.readMessage(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `frame rejects an empty message`() {
        assertNull(DotFraming.frame(ByteArray(0)))
    }

    @Test
    fun `frame rejects a message too large for the 16-bit length`() {
        assertNull(DotFraming.frame(ByteArray(DotFraming.MAX_MESSAGE_LEN + 1)))
    }

    @Test
    fun `frame accepts a message at exactly the max length`() {
        val framed = DotFraming.frame(ByteArray(DotFraming.MAX_MESSAGE_LEN))
        assertEquals(DotFraming.MAX_MESSAGE_LEN + 2, framed!!.size)
        assertEquals(0xFF, framed[0].toInt() and 0xFF)
        assertEquals(0xFF, framed[1].toInt() and 0xFF)
    }
}
