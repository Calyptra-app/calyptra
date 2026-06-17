package com.calyptra.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsResponseValidatorTest {

    /** Builds a minimal DNS query packet for [domain] with the given id/qtype. */
    private fun query(id: Int, domain: String, qtype: Int = 1, qclass: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)            // flags: RD
        out.write(0x00); out.write(0x01)            // QDCOUNT = 1
        out.write(0x00); out.write(0x00)            // ANCOUNT
        out.write(0x00); out.write(0x00)            // NSCOUNT
        out.write(0x00); out.write(0x00)            // ARCOUNT
        for (label in domain.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)                             // root label
        out.write((qtype shr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write((qclass shr 8) and 0xFF); out.write(qclass and 0xFF)
        return out.toByteArray()
    }

    /** A valid response: the query with the QR bit set and ANCOUNT = 1. */
    private fun responseFor(query: ByteArray): ByteArray {
        val r = query.copyOf()
        r[2] = (r[2].toInt() or 0x80).toByte() // QR = 1
        r[7] = 0x01                            // ANCOUNT = 1
        return r
    }

    @Test
    fun `matching response is accepted`() {
        val q = query(0x1234, "example.com")
        assertTrue(DnsResponseValidator.matches(q, responseFor(q)))
    }

    @Test
    fun `same id but different question is rejected`() {
        // The core hardening: an off-path attacker who guesses the 16-bit id but
        // not the question must not have a forged answer accepted.
        val q = query(0x1234, "example.com")
        val forged = responseFor(query(0x1234, "evil.example"))
        assertFalse(DnsResponseValidator.matches(q, forged))
    }

    @Test
    fun `mismatched transaction id is rejected`() {
        val q = query(0x1234, "example.com")
        val r = responseFor(query(0x9999, "example.com"))
        assertFalse(DnsResponseValidator.matches(q, r))
    }

    @Test
    fun `response without QR bit is rejected`() {
        val q = query(0x1234, "example.com")
        val r = q.copyOf() // QR still 0
        assertFalse(DnsResponseValidator.matches(q, r))
    }

    @Test
    fun `different qtype on same name is rejected`() {
        val q = query(0x1234, "example.com", qtype = 1)        // A
        val r = responseFor(query(0x1234, "example.com", qtype = 28)) // AAAA
        assertFalse(DnsResponseValidator.matches(q, r))
    }

    @Test
    fun `qdcount mismatch is rejected`() {
        val q = query(0x1234, "example.com")
        val r = responseFor(q)
        r[5] = 0x02 // QDCOUNT = 2 in the response
        assertFalse(DnsResponseValidator.matches(q, r))
    }

    @Test
    fun `response truncated before end of question is rejected`() {
        val q = query(0x1234, "example.com")
        val full = responseFor(q)
        val truncated = full.copyOf(full.size - 2) // drop QCLASS tail
        assertFalse(DnsResponseValidator.matches(q, truncated))
    }

    @Test
    fun `short packets are rejected`() {
        val q = query(0x1234, "example.com")
        assertFalse(DnsResponseValidator.matches(q, ByteArray(4)))
        assertFalse(DnsResponseValidator.matches(ByteArray(4), responseFor(q)))
    }

    @Test
    fun `compression pointer in query question is treated as malformed`() {
        val q = query(0x1234, "example.com")
        q[12] = 0xC0.toByte() // first label byte becomes a compression pointer
        q[13] = 0x0C.toByte()
        assertFalse(DnsResponseValidator.matches(q, responseFor(q)))
    }
}
