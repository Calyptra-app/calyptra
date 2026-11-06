package com.calyptra.app.vpn.dot

import com.calyptra.app.vpn.dot.FakeConnector.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class DotResolverTest {

    private val clock = FakeClock()
    private val base = DotBackoff.BASE_BACKOFF_MS

    private fun resolver(connector: FakeConnector, maxActive: Int = 8, slotWaitMs: Long = 300) =
        DotResolver(connector.endpoints(), connector, clock::now, maxActive, slotWaitMs)

    @Test
    fun `answers from the first endpoint and reuses the warm connection`() {
        val connector = FakeConnector(clock, Mode.ANSWER, Mode.ANSWER)
        val dot = resolver(connector)
        val q = dnsQuery()
        assertArrayEquals(dnsAnswerFor(q), dot.query(q))
        assertArrayEquals(dnsAnswerFor(q), dot.query(q))
        assertEquals(1, connector.opens[0].get())
        assertEquals(0, connector.opens[1].get())
    }

    @Test
    fun `failed endpoint is skipped during backoff and probed once it expires`() {
        val connector = FakeConnector(clock, Mode.DROP, Mode.ANSWER)
        val dot = resolver(connector)

        assertNotNull(dot.query(dnsQuery())) // ep0 times out (2.5 s), ep1 answers
        assertEquals(1, connector.opens[0].get())

        // Backed off: ep0 is not even attempted, so no connect timeout is paid.
        val before = clock.now()
        assertNotNull(dot.query(dnsQuery()))
        assertEquals(1, connector.opens[0].get())
        assertEquals(before, clock.now())

        // Backoff expired: exactly one recovery probe of ep0.
        clock.advance(base)
        assertNotNull(dot.query(dnsQuery()))
        assertEquals(2, connector.opens[0].get())

        // Probe failed: backoff doubled, so ep0 is still skipped after `base`…
        clock.advance(base)
        dot.query(dnsQuery())
        assertEquals(2, connector.opens[0].get())
        // …and probed again after 2 * base.
        clock.advance(base)
        dot.query(dnsQuery())
        assertEquals(3, connector.opens[0].get())
    }

    @Test
    fun `all endpoints backed off returns null immediately for UDP fallback`() {
        val connector = FakeConnector(clock, Mode.REFUSE, Mode.REFUSE)
        val dot = resolver(connector)
        assertNull(dot.query(dnsQuery()))
        assertNull(dot.query(dnsQuery()))
        assertEquals(1, connector.opens[0].get())
        assertEquals(1, connector.opens[1].get())
    }

    @Test
    fun `successful probe resets backoff to the base window`() {
        val connector = FakeConnector(clock, Mode.REFUSE, Mode.ANSWER)
        val dot = resolver(connector)
        dot.query(dnsQuery()) // failure #1
        clock.advance(base)
        dot.query(dnsQuery()) // failure #2 → 2 * base
        clock.advance(2 * base)
        connector.modes[0] = Mode.ANSWER
        dot.query(dnsQuery()) // probe succeeds → reset
        val opensAfterReset = connector.opens[0].get()

        // Make the warm connection go stale and the endpoint refuse: one failure.
        clock.advance(DotConnectionPolicy.IDLE_TIMEOUT_MS)
        connector.modes[0] = Mode.REFUSE
        dot.query(dnsQuery())
        assertEquals(opensAfterReset + 1, connector.opens[0].get())

        // Back to base (not 4 * base): probed again after one base window.
        clock.advance(base)
        dot.query(dnsQuery())
        assertEquals(opensAfterReset + 2, connector.opens[0].get())
    }

    @Test
    fun `query never spends more than the deadline in DoT`() {
        val connector = FakeConnector(clock, Mode.DROP, Mode.SILENT)
        val dot = resolver(connector)
        val start = clock.now()
        assertNull(dot.query(dnsQuery()))
        assertTrue(clock.now() - start <= DotResolver.QUERY_DEADLINE_MS)
        // ep1 only got what was left of the deadline, not a full connect timeout.
        val ep1Timeout = connector.connectTimeouts[1].single()
        assertEquals(DotResolver.QUERY_DEADLINE_MS - TlsDotConnector.CONNECT_TIMEOUT_MS, ep1Timeout.toLong())
    }

    @Test
    fun `failure under a deadline-truncated timeout does not back the endpoint off`() {
        val connector = FakeConnector(clock, Mode.DROP, Mode.SILENT)
        val dot = resolver(connector)
        dot.query(dnsQuery())
        connector.modes[1] = Mode.ANSWER
        // ep0 is backed off; ep1 was only cut short by the deadline, so it's tried.
        assertNotNull(dot.query(dnsQuery()))
        assertEquals(1, connector.opens[0].get())
        assertEquals(2, connector.opens[1].get())
    }

    @Test
    fun `active connections are bounded and excess queries fall back after a short wait`() {
        val connector = FakeConnector(clock, Mode.BLOCK)
        val dot = resolver(connector, maxActive = 2, slotWaitMs = 50)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val blocked = List(2) { pool.submit(Callable { dot.query(dnsQuery()) }) }
            waitUntil { connector.blockedOpens.get() == 2 }

            val startNs = System.nanoTime()
            assertNull(dot.query(dnsQuery()))
            assertTrue(System.nanoTime() - startNs < TimeUnit.SECONDS.toNanos(2))
            assertEquals(2, connector.opens[0].get()) // third query never opened

            dot.close()
            blocked.forEach { assertNull(it.get(2, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `unexpected exceptions do not leak connection slots`() {
        val connector = FakeConnector(clock, Mode.THROW)
        val dot = resolver(connector, maxActive = 1, slotWaitMs = 10)
        repeat(3) {
            assertNull(dot.query(dnsQuery()))
            clock.advance(DotBackoff.MAX_BACKOFF_MS) // let it be probed again
        }
        assertEquals(3, connector.opens[0].get())
        connector.modes[0] = Mode.ANSWER
        assertNotNull(dot.query(dnsQuery())) // the single slot is still free
    }

    @Test
    fun `close unblocks in-flight connects and slot waiters promptly`() {
        val connector = FakeConnector(clock, Mode.BLOCK)
        // Long slot wait, so only close() can release the waiter in time.
        val dot = resolver(connector, maxActive = 1, slotWaitMs = 30_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val inFlight: Future<ByteArray?> = pool.submit(Callable { dot.query(dnsQuery()) })
            waitUntil { connector.blockedOpens.get() == 1 }
            val waiter: Future<ByteArray?> = pool.submit(Callable { dot.query(dnsQuery()) })
            Thread.sleep(50) // let the waiter park on the slot

            dot.close()
            assertNull(inFlight.get(2, TimeUnit.SECONDS))
            assertNull(waiter.get(2, TimeUnit.SECONDS))
            assertEquals(1, connector.opens[0].get())
            assertNull(dot.query(dnsQuery())) // closed resolver answers nothing
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `close closes idle pooled connections`() {
        val connector = FakeConnector(clock, Mode.ANSWER)
        val dot = resolver(connector)
        assertNotNull(dot.query(dnsQuery()))
        assertTrue(!connector.connections.single().closed) // pooled, still open
        dot.close()
        assertTrue(connector.connections.single().closed)
        assertNull(dot.query(dnsQuery()))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(5)
        }
    }
}
