package com.calyptra.app.vpn.dot

import java.io.Closeable
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-arms the socket read timeout from the remaining [budget] before EVERY read.
 * A socket timeout bounds each read separately, so a frame delivered in many
 * small chunks used to get a fresh window per chunk and could run far past the
 * query deadline. Once the budget is spent, the next read fails immediately.
 */
internal class DeadlineInputStream(
    private val delegate: InputStream,
    private val budget: DotBudget,
    private val capMs: Int,
    private val setReadTimeout: (Int) -> Unit,
) : InputStream() {

    private fun arm() {
        val timeout = budget.timeoutMs(capMs)
        if (timeout <= 0) throw SocketTimeoutException("DoT query deadline exceeded")
        setReadTimeout(timeout)
    }

    override fun read(): Int {
        arm()
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        arm()
        return delegate.read(b, off, len)
    }
}

/**
 * Wall-clock backstop for the steps a read timeout can't bound: the TLS
 * handshake (many reads and writes under one socket timeout) and blocking
 * writes. At the deadline it closes the target, which fails the blocked call.
 * One daemon thread per resolver; [shutdown] when the resolver closes.
 */
internal class DotDeadlineTimer {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dot-deadline").apply { isDaemon = true }
        }

    /** A scheduled close. [fired] tells the caller its target was closed, so a
     *  step that happened to finish at the same moment must not be reused. */
    class Guard internal constructor(private val future: ScheduledFuture<*>?, private val firedFlag: AtomicBoolean) {
        val fired: Boolean get() = firedFlag.get()
        fun cancel() { future?.cancel(false) }
    }

    /** Closes whatever [target] returns once [budget] runs out. */
    fun guard(budget: DotBudget, target: () -> Closeable?): Guard {
        val fired = AtomicBoolean(false)
        val remaining = budget.remainingMs()
        val task = Runnable {
            fired.set(true)
            budget.markTruncated()
            closeQuietly(target())
        }
        if (remaining <= 0) {
            task.run()
            return Guard(null, fired)
        }
        val future = try {
            executor.schedule(task, remaining, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null // resolver closing: close() tears every socket down anyway
        }
        return Guard(future, fired)
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
