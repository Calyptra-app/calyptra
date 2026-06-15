package com.calyptra.app.security

/** Exponential lockout after repeated PIN failures (PIN-L4). Pure function. */
object LockoutPolicy {

    const val MAX_ATTEMPTS = 5
    private const val BASE_MS = 30_000L
    private const val CAP_MS = 300_000L

    /** Returns the epoch-millis until which entry is locked, or 0 if not locked.
     *  30 s on the 5th failure, doubling per failure, capped at 5 min. */
    fun lockoutUntil(failedAttempts: Int, lastFailureAt: Long): Long {
        if (failedAttempts < MAX_ATTEMPTS) return 0L
        val exponent = (failedAttempts - MAX_ATTEMPTS).coerceAtMost(4)
        val duration = (BASE_MS shl exponent).coerceAtMost(CAP_MS)
        return lastFailureAt + duration
    }
}
