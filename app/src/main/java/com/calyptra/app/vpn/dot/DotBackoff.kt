package com.calyptra.app.vpn.dot

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-endpoint failure backoff. After an endpoint fails it is skipped (queries go
 * straight to the next endpoint, or to the caller's UDP fallback) for an
 * exponentially growing window: [baseMs], 2x, 4x … capped at [maxMs]. When the
 * window expires exactly ONE caller is admitted as a recovery probe; everyone
 * else keeps skipping until the probe reports back, so a recovering (or still
 * dead) endpoint isn't stampeded by every in-flight query at once. Any success
 * resets the endpoint to healthy.
 *
 * Time is passed in rather than read, so this is unit-testable with a fake clock.
 */
internal class DotBackoff(
    endpointCount: Int,
    private val baseMs: Long = BASE_BACKOFF_MS,
    private val maxMs: Long = MAX_BACKOFF_MS,
) {

    enum class Admission { NORMAL, PROBE, SKIP }

    private class State {
        var failures = 0
        var backedOffUntilMs = 0L
        var probing = false
    }

    private val states = Array(endpointCount) { State() }

    /** Whether endpoint [index] may be tried at [nowMs]; a PROBE result MUST be
     *  followed by exactly one [onSuccess], [onFailure] or [onAbandon]. */
    fun admit(index: Int, nowMs: Long): Admission = synchronized(this) {
        val s = states[index]
        when {
            s.failures == 0 -> Admission.NORMAL
            nowMs < s.backedOffUntilMs || s.probing -> Admission.SKIP
            else -> {
                s.probing = true
                Admission.PROBE
            }
        }
    }

    fun onSuccess(index: Int) = synchronized(this) {
        val s = states[index]
        s.failures = 0
        s.backedOffUntilMs = 0L
        s.probing = false
    }

    /**
     * Records a failure. A non-probe failure that lands while the endpoint is
     * already backed off (a query that started before the first failure was
     * recorded) is ignored, so a burst of concurrent failures escalates the
     * backoff once rather than once per query.
     */
    fun onFailure(index: Int, nowMs: Long, wasProbe: Boolean) = synchronized(this) {
        val s = states[index]
        if (!wasProbe && (s.probing || nowMs < s.backedOffUntilMs)) return@synchronized
        s.failures++
        s.backedOffUntilMs = nowMs + delayFor(s.failures)
        if (wasProbe) s.probing = false
    }

    /** The attempt ended without a verdict (deadline cut it short, or shutdown):
     *  release the probe slot without changing the endpoint's health. */
    fun onAbandon(index: Int, wasProbe: Boolean) = synchronized(this) {
        if (wasProbe) states[index].probing = false
    }

    private fun delayFor(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 30)
        val delay = baseMs shl shift
        return if (delay <= 0 || delay > maxMs) maxMs else delay
    }

    companion object {
        /** First backoff: long enough that a dropped port 853 costs one timeout per
         *  endpoint per window, not one per query. */
        const val BASE_BACKOFF_MS = 5_000L

        /** Cap: a network that blocks DoT is re-probed every 5 minutes, so
         *  encryption comes back reasonably soon after moving to a friendlier one. */
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}

/**
 * Counting limiter for ACTIVE DoT connections (borrowed or being opened). Unlike
 * [java.util.concurrent.Semaphore], [close] wakes every waiter and makes all
 * future [acquire]s fail fast, so teardown never leaves a query parked here.
 */
internal class DotSlots(private val max: Int) {

    private val lock = ReentrantLock()
    private val freed = lock.newCondition()
    private var inUse = 0
    private var closed = false

    /** Takes a slot, waiting at most [timeoutMs]. False on timeout or if closed;
     *  only a true result must be paired with [release]. */
    fun acquire(timeoutMs: Long): Boolean = lock.withLock {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && inUse >= max) {
            if (remainingNs <= 0) return false
            remainingNs = freed.awaitNanos(remainingNs)
        }
        if (closed) return false
        inUse++
        true
    }

    fun release() = lock.withLock {
        if (inUse > 0) inUse--
        freed.signal()
    }

    fun close() = lock.withLock {
        closed = true
        freed.signalAll()
    }

    /** Slots currently held (for tests / diagnostics). */
    fun inUse(): Int = lock.withLock { inUse }
}
