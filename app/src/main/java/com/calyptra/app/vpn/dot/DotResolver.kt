package com.calyptra.app.vpn.dot

import com.calyptra.app.vpn.DnsResponseValidator
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Forwards allowed DNS queries to upstream resolvers over **DNS-over-TLS**
 * (RFC 7858): a TLS-encrypted TCP connection on port 853 carrying length-prefixed
 * DNS messages ([DotFraming]). This hides the child's queries from the on-path
 * ISP/network that plain UDP/53 exposes, and authenticates the resolver.
 *
 * Resolvers are tried in order; [query] returns the first valid answer, or null if
 * every endpoint fails — the caller (AdBlockVpnService) then fails open to plain
 * UDP so DNS never black-holes. Filtering is enforced at the resolver, so it
 * survives on both transports; only encryption is lost on the UDP fallback.
 *
 * Connections are reused across queries (no TLS handshake per lookup) via a small
 * idle pool gated by [DotConnectionPolicy]. The socket/TLS I/O here can't be
 * unit-tested with the project's plain-JUnit stack; the testable wire-format and
 * reuse-window logic live in the pure [DotFraming] / [DotConnectionPolicy] objects.
 *
 * Endpoints are addressed by **IP** (not hostname) to avoid a bootstrap loop — our
 * own DNS is the VPN — while SNI + certificate hostname verification still bind the
 * connection to [DotEndpoint.tlsHost].
 *
 * Thread-safety: every connection lives in exactly one of two lock-guarded sets —
 * [idle] (poolable) or [active] (borrowed, in use by one caller) — so a socket is
 * never used by two threads at once. [close] drains BOTH sets, so teardown
 * deterministically closes in-flight sockets too (unblocking their blocking I/O)
 * rather than waiting on the read timeout.
 *
 * @param protect routes a socket around the VPN tunnel before it connects, so DoT
 *   traffic isn't itself captured by the tunnel (VpnService.protect).
 */
internal class DotResolver(
    private val endpoints: List<DotEndpoint>,
    private val protect: (Socket) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    data class DotEndpoint(val ip: String, val tlsHost: String, val port: Int = 853)

    private class PooledConn(
        val endpointIndex: Int,
        val socket: SSLSocket,
        val input: InputStream,
        val output: OutputStream,
        var idleSinceMs: Long,
    )

    private val lock = Any()
    private val idle = ArrayDeque<PooledConn>()
    /** Connections currently borrowed by a query. Tracked so [close] can tear them
     *  down on stop instead of leaving an in-flight socket open until its timeout. */
    private val active = HashSet<PooledConn>()
    @Volatile private var closed = false

    /**
     * Resolves [dnsData] over DoT. Returns the validated upstream answer, or null
     * if no endpoint produced a matching response (caller falls back to UDP).
     * Blocking; call from a [kotlinx.coroutines.Dispatchers.IO] context.
     */
    fun query(dnsData: ByteArray): ByteArray? {
        if (closed) return null
        val framed = DotFraming.frame(dnsData) ?: return null
        for (index in endpoints.indices) {
            queryEndpoint(index, framed, dnsData)?.let { return it }
        }
        return null
    }

    /** Drains and closes every connection — idle AND active — so an in-flight
     *  exchange's blocking I/O is unblocked promptly on teardown. */
    fun close() {
        val drained = synchronized(lock) {
            closed = true
            val all = idle + active
            idle.clear()
            active.clear()
            all
        }
        drained.forEach { closeQuietly(it.socket) }
    }

    /**
     * One endpoint, at most two attempts: a reused warm connection (fast path),
     * then — if that connection was stale/broken — exactly one fresh connection.
     * Both failing means this endpoint is down; the caller tries the next.
     */
    private fun queryEndpoint(index: Int, framed: ByteArray, dnsData: ByteArray): ByteArray? {
        borrowIdle(index)?.let { reused ->
            exchange(reused, framed, dnsData)?.let { return it }
            // reused conn was stale/broken (exchange closed it) — try a fresh one.
        }
        val fresh = open(index) ?: return null // connect/handshake failed or resolver closed
        return exchange(fresh, framed, dnsData)
    }

    /**
     * Writes the framed query and reads back one framed answer on [conn],
     * validating it against the query we sent. On success the connection is
     * recycled for reuse; on any failure (I/O error, no/short response, or a
     * response that doesn't match the query) it is closed and null is returned.
     *
     * The write of a framed DNS query (<512 bytes in practice) fits the socket send
     * buffer and so does not block on a healthy connection; the read is bounded by
     * the socket's soTimeout, and a stuck connection is torn down by [close] on
     * teardown — so this has no unbounded blocking path for DNS-sized payloads.
     */
    private fun exchange(conn: PooledConn, framed: ByteArray, dnsData: ByteArray): ByteArray? {
        return try {
            conn.output.write(framed)
            conn.output.flush()
            val response = DotFraming.readMessage(conn.input)
            if (response != null && DnsResponseValidator.matches(dnsData, response)) {
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
            closeQuietly(conn.socket) // aged out — drop and keep scanning
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
        if (!keep) closeQuietly(conn.socket)
    }

    /** Drops a borrowed connection: removes it from the active set and closes it. */
    private fun discard(conn: PooledConn) {
        synchronized(lock) { active.remove(conn) }
        closeQuietly(conn.socket)
    }

    /** Opens a fresh, validated TLS connection to endpoint [index] and registers it
     *  as active, or returns null on connect/handshake failure or if the resolver
     *  has been closed. */
    private fun open(index: Int): PooledConn? {
        val ep = endpoints[index]
        val plain = Socket()
        val ssl: SSLSocket
        try {
            // Route around the tunnel BEFORE connecting so the SYN doesn't loop
            // back through our own VPN.
            protect(plain)
            plain.connect(InetSocketAddress(ep.ip, ep.port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, ep.tlsHost, ep.port, /* autoClose = */ true) as SSLSocket
            // Bound handshake/read on the wrapping socket too, not just the delegate.
            ssl.soTimeout = READ_TIMEOUT_MS
        } catch (e: Exception) {
            closeQuietly(plain)
            return null
        }
        try {
            // "HTTPS" endpoint identification makes the handshake verify the cert's
            // hostname against ep.tlsHost. Without it, a wrapped SSLSocket checks
            // the chain but NOT the hostname — a valid cert for the wrong host would
            // pass. (API 24+; minSdk is 26.) createSocket(..., tlsHost, ...) sets SNI.
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
        } catch (e: Exception) {
            closeQuietly(ssl) // autoClose tears down the underlying plain socket too
            return null
        }
        val conn = PooledConn(
            endpointIndex = index,
            socket = ssl,
            input = ssl.inputStream,
            output = ssl.outputStream,
            idleSinceMs = nowMs(),
        )
        val registered = synchronized(lock) {
            if (closed) false else { active.add(conn); true }
        }
        if (!registered) {
            closeQuietly(ssl) // closed during handshake — don't leak the new socket
            return null
        }
        return conn
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Best-effort close; nothing actionable on failure.
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 2500
    }
}
