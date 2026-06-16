package com.calyptra.app.security

import java.security.MessageDigest
import java.util.Base64

/** Persistence boundary for PIN state — implemented by PreferencesRepository,
 *  faked in unit tests. Hash and salt are Base64; "" means no PIN set. */
interface PinStore {
    suspend fun getPinHash(): String
    suspend fun getPinSalt(): String
    suspend fun getFailedAttempts(): Int
    suspend fun getLockoutUntil(): Long
    suspend fun setPin(hash: String, salt: String)
    suspend fun setFailureState(attempts: Int, lockoutUntil: Long)
}

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Wrong(val attemptsLeft: Int) : VerifyResult
    data class LockedOut(val untilMillis: Long) : VerifyResult
}

/**
 * Parental PIN verification with persistent lockout and an in-memory grace
 * session (PIN-L1, PIN-L4). The grace session deliberately does not survive
 * process death — backgrounding the app ends parental context.
 */
class PinManager(
    private val store: PinStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hasher: (String, ByteArray) -> ByteArray = PinHasher::hash
) {

    @Volatile
    private var graceUntil = 0L

    suspend fun isPinSet(): Boolean = store.getPinHash().isNotEmpty()

    suspend fun setPin(pin: String) {
        val salt = PinHasher.newSalt()
        val hash = hasher(pin, salt)
        store.setPin(encode(hash), encode(salt))
        store.setFailureState(0, 0L)
    }

    suspend fun verify(pin: String): VerifyResult {
        val lockedUntil = store.getLockoutUntil()
        if (clock() < lockedUntil) {
            // Locked out: never evaluates the hash, so brute-forcing burns no CPU
            // and timing reveals nothing.
            return VerifyResult.LockedOut(lockedUntil)
        }

        // Fail safe: any error reading/decoding the stored hash+salt or running
        // the KDF (e.g. an empty/malformed salt on a half-written setup, an
        // unsupported algorithm, a bad Base64 value) must surface as a wrong PIN,
        // never an uncaught exception that kills the process during PIN entry.
        val ok = try {
            val expected = decode(store.getPinHash())
            val salt = decode(store.getPinSalt())
            MessageDigest.isEqual(hasher(pin, salt), expected)
        } catch (e: Exception) {
            false
        }

        return if (ok) {
            store.setFailureState(0, 0L)
            graceUntil = clock() + GRACE_SESSION_MS
            VerifyResult.Ok
        } else {
            val attempts = store.getFailedAttempts() + 1
            val until = LockoutPolicy.lockoutUntil(attempts, clock())
            store.setFailureState(attempts, until)
            if (until > 0L) {
                VerifyResult.LockedOut(until)
            } else {
                VerifyResult.Wrong(attemptsLeft = LockoutPolicy.MAX_ATTEMPTS - attempts)
            }
        }
    }

    fun isInGraceSession(): Boolean = clock() < graceUntil

    fun endGraceSession() {
        graceUntil = 0L
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    companion object {
        private const val GRACE_SESSION_MS = 5 * 60_000L
    }
}
