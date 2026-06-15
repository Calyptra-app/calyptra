package com.calyptra.app.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinManagerTest {

    /** In-memory PinStore so the manager is tested without DataStore/Android. */
    private class FakePinStore : PinStore {
        var pinHash = ""
        var pinSalt = ""
        var failedAttempts = 0
        var lockoutUntil = 0L

        override suspend fun getPinHash() = pinHash
        override suspend fun getPinSalt() = pinSalt
        override suspend fun getFailedAttempts() = failedAttempts
        override suspend fun getLockoutUntil() = lockoutUntil
        override suspend fun setPin(hash: String, salt: String) {
            pinHash = hash; pinSalt = salt
        }
        override suspend fun setFailureState(attempts: Int, lockoutUntil: Long) {
            failedAttempts = attempts; this.lockoutUntil = lockoutUntil
        }
    }

    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    @Test
    fun `pin is not set initially and set after setPin`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        assertFalse(manager.isPinSet())
        manager.setPin("1234")
        assertTrue(manager.isPinSet())
    }

    @Test
    fun `setup then verify roundtrip succeeds`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        assertEquals(VerifyResult.Ok, manager.verify("1234"))
    }

    @Test
    fun `wrong pin returns Wrong with decreasing attempts left`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        assertEquals(VerifyResult.Wrong(attemptsLeft = 4), manager.verify("0000"))
        assertEquals(VerifyResult.Wrong(attemptsLeft = 3), manager.verify("0000"))
    }

    @Test
    fun `fifth consecutive failure returns LockedOut`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        repeat(4) { manager.verify("0000") }
        val result = manager.verify("0000")
        assertEquals(VerifyResult.LockedOut(untilMillis = now + 30_000L), result)
    }

    @Test
    fun `verify during lockout returns LockedOut without evaluating the hash`() = runBlocking {
        var hashCalls = 0
        val store = FakePinStore()
        val manager = PinManager(store, clock, hasher = { pin, salt ->
            hashCalls++
            PinHasher.hash(pin, salt)
        })
        manager.setPin("1234")
        repeat(5) { manager.verify("0000") }

        val callsBefore = hashCalls
        val result = manager.verify("1234") // correct pin, but locked out
        assertTrue(result is VerifyResult.LockedOut)
        assertEquals("hash must not run while locked out", callsBefore, hashCalls)
    }

    @Test
    fun `lockout persists across a new manager instance with the same store`() = runBlocking {
        val store = FakePinStore()
        val first = PinManager(store, clock)
        first.setPin("1234")
        repeat(5) { first.verify("0000") }

        val second = PinManager(store, clock) // simulates process death + restart
        assertTrue(second.verify("1234") is VerifyResult.LockedOut)
    }

    @Test
    fun `lockout expires with time and correct pin then succeeds and resets counter`() = runBlocking {
        val store = FakePinStore()
        val manager = PinManager(store, clock)
        manager.setPin("1234")
        repeat(5) { manager.verify("0000") }

        now += 31_000L // past the 30 s lockout
        assertEquals(VerifyResult.Ok, manager.verify("1234"))
        assertEquals("failure counter resets on success", 0, store.failedAttempts)
        assertEquals(0L, store.lockoutUntil)
    }

    @Test
    fun `success starts a 5-minute grace session`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        assertFalse(manager.isInGraceSession())
        manager.verify("1234")
        assertTrue(manager.isInGraceSession())

        now += 4 * 60_000L
        assertTrue("still within 5 min", manager.isInGraceSession())
        now += 2 * 60_000L
        assertFalse("expired after 5 min", manager.isInGraceSession())
    }

    @Test
    fun `endGraceSession ends it immediately`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        manager.verify("1234")
        assertTrue(manager.isInGraceSession())
        manager.endGraceSession()
        assertFalse(manager.isInGraceSession())
    }

    @Test
    fun `wrong pin does not start a grace session`() = runBlocking {
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("1234")
        manager.verify("0000")
        assertFalse(manager.isInGraceSession())
    }

    @Test
    fun `setPin replaces the old pin and re-salts`() = runBlocking {
        val store = FakePinStore()
        val manager = PinManager(store, clock)
        manager.setPin("1234")
        val firstSalt = store.pinSalt
        manager.setPin("5678")
        assertTrue(manager.verify("5678") is VerifyResult.Ok)
        assertFalse("old pin must no longer verify; counter consumed", manager.verify("1234") is VerifyResult.Ok)
        assertFalse("salt must rotate on pin change", firstSalt == store.pinSalt)
    }
}
