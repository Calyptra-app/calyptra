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
    fun `fresh install set then verify correct and wrong pin does not throw`() = runBlocking {
        // Reproduces the PIN-entry crash path: a clean install where the parent
        // just set a new PIN, then verifies. Must never throw (BUG: PinHasher
        // crash on verify) — correct pin -> Ok, wrong pin -> Wrong.
        val manager = PinManager(FakePinStore(), clock)
        manager.setPin("2468")
        assertEquals(VerifyResult.Ok, manager.verify("2468"))
        assertTrue(manager.verify("1357") is VerifyResult.Wrong)
    }

    @Test
    fun `verify fails safe instead of crashing when stored salt is empty`() = runBlocking {
        // A half-written / corrupted setup can leave an empty salt; decoding it
        // yields a 0-length ByteArray, which made PBKDF2 throw
        // IllegalArgumentException("the salt parameter must not be empty") and
        // crash the process during PIN entry. It must now surface as a wrong PIN.
        val store = FakePinStore().apply {
            pinHash = "" // non-empty hash string would still decode; "" => empty
            pinSalt = ""
        }
        // Force isPinSet()-style state: a hash is present but the salt is empty.
        store.pinHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        val manager = PinManager(store, clock)

        val result = manager.verify("1234")
        assertTrue("empty salt must fail safe as Wrong, not throw", result is VerifyResult.Wrong)
    }

    @Test
    fun `verify fails safe when stored hash is not valid base64`() = runBlocking {
        val store = FakePinStore().apply {
            pinHash = "!!!not-base64!!!"
            pinSalt = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        }
        val manager = PinManager(store, clock)
        val result = manager.verify("1234")
        assertTrue("malformed stored hash must fail safe, not throw", result is VerifyResult.Wrong)
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
