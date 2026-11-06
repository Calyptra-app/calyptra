package com.calyptra.app.vpn.dot

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class DotDeadlineTest {
    @Test fun fragmentedResponseMustRespectWholeQueryDeadline() {
        val clock = FakeClock()
        val response = DotFraming.frame(dnsAnswerFor(dnsQuery()))!!
        val connector = object : DotConnector {
            override fun open(endpoint: DotResolver.DotEndpoint, budget: DotBudget, register: (Closeable) -> Boolean): DotConnection {
                return object : DotConnection {
                    var pos = 0
                    override val output = ByteArrayOutputStream()
                    override val input = object : InputStream() {
                        override fun read(): Int {
                            if (pos == response.size) return -1
                            clock.advance(1000)
                            return response[pos++].toInt() and 255
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val value = read()
                            if (value < 0) return -1
                            b[off] = value.toByte()
                            return 1
                        }
                    }
                    override fun setReadTimeout(ms: Int) {}
                    override fun close() {}
                }
            }
        }
        val resolver = DotResolver(listOf(DotResolver.DotEndpoint("127.0.0.1", "example.com")), connector, clock::now)
        val start = clock.now()
        resolver.query(dnsQuery())
        assertTrue("Elapsed simulated time: ${clock.now() - start} ms", clock.now() - start <= DotResolver.QUERY_DEADLINE_MS)
    }
}
