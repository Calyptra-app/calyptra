package com.calyptra.app.vpn.dot

import com.calyptra.app.vpn.DnsResponseValidator
import java.io.Closeable
import java.net.Socket

/**
 * Forwards allowed DNS queries to upstream resolvers over **DNS-over-TLS**
 * (RFC 7858): a TLS-encrypted TCP connection on port 853 carrying length-prefixed
 * DNS messages ([DotFraming]). This hides the child's queries from the on-path
 * ISP/network that plain UDP/53 exposes, and authenticates the resolver.
 *
 * Resolvers are tried in order; [query] returns the first valid answer, or null —
 * the caller (AdBlockVpnService) then fails open to plain UDP so DNS never
 * black-holes. Filtering is enforced at the resolver, so it survives on both
 * transports; only encryption is lost on the UDP fallback.
 *
 * Latency / resource bounds, so a network that silently drops port 853 or a burst
 * of lookups can't stall DNS:
 * - **Backoff** ([DotBackoff]): a failed endpoint is skipped for 5 s, doubling up
 *   to 5 min, with a single recovery probe when the window expires.
 * - **Deadline** ([QUERY_DEADLINE_MS]): one query spends at most ~3 s in DoT
 *   across all endpoints before returning null.
 * - **Active bound** ([DotSlots]): at most [DotConnectionPolicy.MAX_ACTIVE_CONNECTIONS]
 *   queries hold or open a connection at once; others wait briefly
 *   ([SLOT_WAIT_MS]) and then return null rather than queue behind handshakes.
 *
 * Connections are reused across queries (no TLS handshake per lookup) via a small
 * idle pool gated by [DotConnectionPolicy]. Socket/TLS I/O lives behind
 * [DotConnector] ([TlsDotConnector] in production) so this class is unit-testable
 * with fakes.
 *
 * Endpoints are addressed by **IP** (not hostname) to avoid a bootstrap loop — our
 * own DNS is the VPN — while SNI + certificate hostname verification still bind the
 * connection to [DotEndpoint.tlsHost].
 *
 * Thread-safety: every open connection lives in exactly one of two lock-guarded
 * sets — [idle] (poolable) or [active] (borrowed, in use by one caller) — and a
 * connect/handshake in progress is tracked in [pending]. [close] closes all three
 * and wakes slot waiters, so teardown returns every caller promptly instead of
 * waiting on socket timeouts.
 */
internal class DotResolver(
    private val endpoints: List<DotEndpoint>,
    private val connector: DotConnector,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    maxActiveConnections: Int = DotConnectionPolicy.MAX_ACTIVE_CONNECTIONS,
    private val slotWaitMs: Long = SLOT_WAIT_MS,
) {

    /** Production constructor: real TLS sockets, routed around the tunnel by [protect]. */
    constructor(endpoints: List<DotEndpoint>, protect: (Socket) -> Boolean) :
        this(endpoints, TlsDotConnector(protect))

    data class DotEndpoint(val ip: String, val tlsHost: String, val port: Int = 853)

    private class PooledConn(
        val endpointIndex: Int,
        val conn: DotConnection,
        var idleSinceMs: Long,
    )

    /** A connect/handshake in flight; [socket] is closed by [close] to abort it. */
    private class PendingOpen {
        // Read by the deadline timer thread.
        @Volatile var socket: Closeable? = null
    }

    private val lock = Any()
    private val idle = ArrayDeque<PooledConn>()
    private val active = HashSet<PooledConn>()
    private val pending = HashSet<PendingOpen>()
    private val backoff = DotBackoff(endpoints.size)
    private val slots = DotSlots(maxActiveConnections)
    private val deadlineTimer = DotDeadlineTimer()
    @Volatile private var closed = false

    /**
     * Resolves [dnsData] over DoT. Returns the validated upstream answer, or null
     * if no endpoint produced a matching response within the deadline, every
     * endpoint is backed off, or no connection slot freed up in time (caller falls
     * back to UDP). Blocking; call from a [kotlinx.coroutines.Dispatchers.IO] context.
     */
    fun query(dnsData: ByteArray): ByteArray? {
        if (closed) return null
        val framed = DotFraming.frame(dnsData) ?: return null
        // One slot per query: a query uses at most one connection at a time.
        if (!slots.acquire(slotWaitMs)) return null
        try {
            val budget = DotBudget(nowMs, nowMs() + QUERY_DEADLINE_MS)
            for (index in endpoints.indices) {
                if (closed || budget.expired()) return null
                val admission = backoff.admit(index, nowMs())
                if (admission == DotBackoff.Admission.SKIP) continue
                tryEndpoint(index, admission, framed, dnsData, budget)?.let { return it }
            }
            return null
        } finally {
            slots.release()
        }
    }

    /** Drains and closes every connection — idle, active AND mid-connect — and
     *  wakes slot waiters, so every in-flight or waiting query returns promptly. */
    fun close() {
        val toClose = synchronized(lock) {
            closed = true
            val all = idle.map { it.conn } + active.map { it.conn } + pending.mapNotNull { it.socket }
            idle.clear()
            active.clear()
            pending.clear()
            all
        }
        slots.close()
        deadlineTimer.shutdown()
        toClose.forEach { closeQuietly(it) }
    }

    /** Tries one endpoint and reports the outcome to [backoff] exactly once. */
    private fun tryEndpoint(
        index: Int,
        admission: DotBackoff.Admission,
        framed: ByteArray,
        dnsData: ByteArray,
        budget: DotBudget,
    ): ByteArray? {
        val wasProbe = admission == DotBackoff.Admission.PROBE
        var answer: ByteArray? = null
        try {
            answer = queryEndpoint(index, framed, dnsData, budget)
        } finally {
            when {
                answer != null -> backoff.onSuccess(index)
                // No verdict: the deadline cut a timeout short, or we're shutting down.
                closed || budget.truncated -> backoff.onAbandon(index, wasProbe)
                else -> backoff.onFailure(index, nowMs(), wasProbe)
            }
        }
        return answer
    }

    /**
     * One endpoint, at most two attempts: a reused warm connection (fast path),
     * then — if that connection was stale/broken — exactly one fresh connection.
     */
    private fun queryEndpoint(index: Int, framed: ByteArray, dnsData: ByteArray, budget: DotBudget): ByteArray? {
        borrowIdle(index)?.let { reused ->
            exchange(reused, framed, dnsData, budget)?.let { return it }
            // reused conn was stale/broken (exchange closed it) — try a fresh one.
        }
        if (budget.expired()) return null
        val fresh = open(index, budget) ?: return null
        return exchange(fresh, framed, dnsData, budget)
    }

    /**
     * Writes the framed query and reads back one framed answer on [conn],
     * validating it against the query we sent. On success the connection is
     * recycled for reuse; on any failure (I/O error, timeout, no/short response, or
     * a response that doesn't match the query) it is closed and null is returned.
     * Each read is bounded by the smaller of the read timeout and the remaining
     * query deadline.
     */
    private fun exchange(conn: PooledConn, framed: ByteArray, dnsData: ByteArray, budget: DotBudget): ByteArray? {
        return try {
            val timeout = budget.timeoutMs(TlsDotConnector.READ_TIMEOUT_MS)
            if (timeout <= 0) {
                recycle(conn) // healthy but out of time: keep it warm for the next query
                return null
            }
            conn.conn.setReadTimeout(timeout)
            // Backstop for the write, which no socket timeout bounds.
            val guard = deadlineTimer.guard(budget) { conn.conn }
            val response = try {
                conn.conn.output.write(framed)
                conn.conn.output.flush()
                // Each read of the (possibly fragmented) frame gets only what is
                // left of the query deadline, not a fresh full window.
                DotFraming.readMessage(
                    DeadlineInputStream(conn.conn.input, budget, TlsDotConnector.READ_TIMEOUT_MS, conn.conn::setReadTimeout)
                )
            } finally {
                guard.cancel()
            }
            if (guard.fired) {
                discard(conn) // closed by the backstop: never return it to the pool
                null
            } else if (response != null && DnsResponseValidator.matches(dnsData, response)) {
                recycle(conn)
                response
            } else {
                discard(conn)
                null
            }
        } catch (e: Exception) {
            discard(conn)
            null
        }
    }

    /** Pops the first still-reusable idle connection for [index] (moving it to the
     *  active set), discarding any that have aged past the reuse window. */
    private fun borrowIdle(index: Int): PooledConn? = synchronized(lock) {
        if (closed) return@synchronized null
        val now = nowMs()
        val it = idle.iterator()
        while (it.hasNext()) {
            val conn = it.next()
            if (conn.endpointIndex != index) continue
            it.remove()
            if (DotConnectionPolicy.isReusable(conn.idleSinceMs, now)) {
                active.add(conn)
                return@synchronized conn
            }
            closeQuietly(conn.conn) // aged out — drop and keep scanning
        }
        null
    }

    /** Returns a healthy connection from the active set to the idle pool, or closes
     *  it if the pool is full or the resolver is shutting down. */
    private fun recycle(conn: PooledConn) {
        conn.idleSinceMs = nowMs()
        val keep = synchronized(lock) {
            active.remove(conn)
            if (closed || idle.size >= DotConnectionPolicy.MAX_IDLE_CONNECTIONS) {
                false
            } else {
                idle.addLast(conn)
                true
            }
        }
        if (!keep) closeQuietly(conn.conn)
    }

    /** Drops a borrowed connection: removes it from the active set and closes it. */
    private fun discard(conn: PooledConn) {
        synchronized(lock) { active.remove(conn) }
        closeQuietly(conn.conn)
    }

    /** Opens a fresh connection to endpoint [index] and registers it as active, or
     *  returns null on failure or if the resolver has been closed. The in-progress
     *  socket is tracked in [pending] so [close] can abort the connect/handshake. */
    private fun open(index: Int, budget: DotBudget): PooledConn? {
        val attempt = PendingOpen()
        val register: (Closeable) -> Boolean = { socket ->
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    attempt.socket = socket
                    pending.add(attempt)
                    true
                }
            }
        }
        // The handshake is many reads/writes under one socket timeout; close the
        // in-progress socket at the query deadline so it can't overrun it.
        val guard = deadlineTimer.guard(budget) { attempt.socket }
        val conn = try {
            connector.open(endpoints[index], budget, register)
        } catch (e: Exception) {
            null
        } finally {
            guard.cancel()
            synchronized(lock) { pending.remove(attempt) }
        }
        if (conn != null && guard.fired) {
            closeQuietly(conn) // its socket was closed by the backstop
            return null
        }
        if (conn == null) return null
        val pooled = PooledConn(endpointIndex = index, conn = conn, idleSinceMs = nowMs())
        val registered = synchronized(lock) {
            if (closed) false else { active.add(pooled); true }
        }
        if (!registered) {
            closeQuietly(conn) // closed during handshake — don't leak the new socket
            return null
        }
        return pooled
    }

    companion object {
        /** Total time one query may spend in DoT across all endpoints. Just above a
         *  single connect timeout, so one dead endpoint can't push a lookup past
         *  ~3 s before the UDP fallback, while a slow-but-alive one still answers. */
        const val QUERY_DEADLINE_MS = 3_000L

        /** How long a query waits for an active-connection slot before falling back
         *  to UDP. Short: a warm DoT exchange takes tens of ms, so a slot normally
         *  frees quickly; if it doesn't, DoT is saturated and UDP is the better bet. */
        const val SLOT_WAIT_MS = 300L
    }
}
