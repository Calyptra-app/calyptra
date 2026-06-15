package com.calyptra.app.vpn

/**
 * Crash-loop breaker for VPN startup (PWR-L1): allows retries after a failure
 * unless 3 failures landed within a 5-minute sliding window — then the service
 * should stop retrying and alert the parent instead of looping silently.
 */
class RestartGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 3,
    private val windowMs: Long = 5 * 60_000L
) {

    private val failures = ArrayDeque<Long>()

    /** Records a startup failure. Returns true if another retry is allowed. */
    @Synchronized
    fun shouldRetryAfterFailure(): Boolean {
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > windowMs) {
            failures.removeFirst()
        }
        return failures.size < maxFailures
    }

    /** Call on successful VPN establishment. */
    @Synchronized
    fun reset() {
        failures.clear()
    }
}
