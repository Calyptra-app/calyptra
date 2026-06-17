package com.calyptra.app.vpn.dot

/**
 * Decides when a pooled, idle DoT (TLS-over-TCP) connection may be reused instead
 * of opening a fresh one. Reusing a warm connection avoids a full TLS handshake
 * per query — the latency and battery win that makes DoT viable on a phone — but
 * a connection idle too long has likely been closed by the resolver (RFC 7858
 * §3.4 lets servers drop idle connections), so reusing it would just fail and add
 * a round-trip before we reconnect.
 *
 * Pure and stateless — fed the current time rather than reading a clock — so it is
 * unit-testable without sockets, matching the repo's other decision objects
 * (LockoutPolicy, VpnWatchdogPolicy, BlocklistUpdatePolicy).
 */
internal object DotConnectionPolicy {

    /**
     * Reuse an idle connection only within this window. Kept conservatively below
     * the idle timeouts public DoT resolvers advertise (~10s+) so we almost never
     * pick a connection the server has already torn down.
     */
    const val IDLE_TIMEOUT_MS = 10_000L

    /** Max idle connections kept warm in the pool; extras are closed on return. */
    const val MAX_IDLE_CONNECTIONS = 4

    /**
     * True if a connection last used at [idleSinceMs] may still be reused at
     * [nowMs]. A non-positive elapsed time (clock moved backwards / skew) is
     * treated as not reusable — fall through to a fresh, known-good connection
     * rather than trust an ambiguous timestamp.
     */
    fun isReusable(idleSinceMs: Long, nowMs: Long): Boolean {
        val elapsed = nowMs - idleSinceMs
        return elapsed in 0 until IDLE_TIMEOUT_MS
    }
}
