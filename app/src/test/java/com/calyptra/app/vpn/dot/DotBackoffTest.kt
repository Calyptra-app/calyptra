package com.calyptra.app.vpn.dot

import com.calyptra.app.vpn.dot.DotBackoff.Admission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DotBackoffTest {

    private val base = 5_000L
    private val max = 60_000L
    private val backoff = DotBackoff(endpointCount = 2, baseMs = base, maxMs = max)

    @Test
    fun `healthy endpoint is admitted normally`() {
        assertEquals(Admission.NORMAL, backoff.admit(0, nowMs = 0))
    }

    @Test
    fun `failure skips the endpoint until the window expires`() {
        backoff.onFailure(0, nowMs = 0, wasProbe = false)
        assertEquals(Admission.SKIP, backoff.admit(0, nowMs = base - 1))
        assertEquals(Admission.NORMAL, backoff.admit(1, nowMs = 1)) // other endpoint unaffected
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = base))
    }

    @Test
    fun `only one recovery probe at a time`() {
        backoff.onFailure(0, nowMs = 0, wasProbe = false)
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = base))
        assertEquals(Admission.SKIP, backoff.admit(0, nowMs = base + 1))
        backoff.onAbandon(0, wasProbe = true) // no verdict: slot freed, health unchanged
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = base + 2))
    }

    @Test
    fun `failed probes back off exponentially up to the cap`() {
        backoff.onFailure(0, nowMs = 0, wasProbe = false) // 5 s
        var now = 0L
        for (expected in listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L)) {
            now = nextProbe(now)
            backoff.onFailure(0, now, wasProbe = true)
            assertEquals(Admission.SKIP, backoff.admit(0, now + expected - 1))
            assertEquals(Admission.PROBE, backoff.admit(0, now + expected))
            backoff.onAbandon(0, wasProbe = true)
        }
    }

    @Test
    fun `success resets to healthy`() {
        backoff.onFailure(0, nowMs = 0, wasProbe = false)
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = base))
        backoff.onSuccess(0)
        assertEquals(Admission.NORMAL, backoff.admit(0, nowMs = base))
        backoff.onFailure(0, nowMs = base, wasProbe = false)
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = 2 * base)) // base window again
    }

    @Test
    fun `concurrent failures during backoff escalate only once`() {
        backoff.onFailure(0, nowMs = 0, wasProbe = false)
        backoff.onFailure(0, nowMs = 10, wasProbe = false)
        backoff.onFailure(0, nowMs = 20, wasProbe = false)
        assertEquals(Admission.PROBE, backoff.admit(0, nowMs = base))
    }

    @Test
    fun `slots bound concurrent holders and close wakes waiters`() {
        val slots = DotSlots(max = 1)
        assertTrue(slots.acquire(10))
        assertFalse(slots.acquire(10)) // full: times out
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waiter = pool.submit<Boolean> { slots.acquire(30_000) }
            Thread.sleep(50)
            slots.close()
            assertFalse(waiter.get(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        slots.release()
        assertFalse(slots.acquire(10)) // closed for good
    }

    private fun nextProbe(from: Long): Long {
        var t = from
        while (backoff.admit(0, t) != Admission.PROBE) t += 1_000
        return t
    }
}
