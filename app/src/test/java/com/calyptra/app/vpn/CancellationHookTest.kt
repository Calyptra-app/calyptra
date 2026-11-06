package com.calyptra.app.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The packet loop's wake-on-cancel hook (review follow-up to #72). */
class CancellationHookTest {

    /** Reviewer's reproduction: the only dispatcher thread is blocked (the
     *  reader in poll()), so a dispatched watcher could never start. */
    @Test
    fun `cancellation wakes a blocked reader even when no dispatcher thread is free`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val woke = CountDownLatch(1)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val loop = scope.launch {
            coroutineScope {
                val waker = onCancellation { woke.countDown(); releaseRead.countDown() }
                try {
                    enteredRead.countDown()
                    releaseRead.await(2, TimeUnit.SECONDS) // stands in for poll()
                } finally {
                    waker.cancel()
                }
            }
        }
        try {
            assertTrue(enteredRead.await(1, TimeUnit.SECONDS))
            loop.cancel()
            assertTrue("Cancellation did not run reader.wake()", woke.await(200, TimeUnit.MILLISECONDS))
        } finally {
            releaseRead.countDown()
            loop.join()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `hook registered in an already-cancelled scope fires immediately`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()
        scope.onCancellation { calls.incrementAndGet() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancelling the hook itself after normal completion is harmless`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob())
        val hook = scope.onCancellation { calls.incrementAndGet() }
        hook.cancel() // loop ended on its own; wake on a closing reader is a guarded no-op
        assertTrue(calls.get() <= 1)
        scope.cancel()
    }
}
