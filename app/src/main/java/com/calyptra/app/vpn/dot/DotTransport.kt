package com.calyptra.app.vpn.dot

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One open, validated DoT connection: a byte stream pair plus a per-read timeout. */
internal interface DotConnection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Bounds each blocking read on this connection. Must be > 0. */
    fun setReadTimeout(ms: Int)
}

/**
 * Opens DoT connections. The seam between [DotResolver]'s pooling / backoff /
 * deadline logic (unit-tested with fakes) and the real socket + TLS I/O
 * ([TlsDotConnector]).
 */
internal interface DotConnector {
    /**
     * Opens a connection to [endpoint], bounding every blocking step by [budget].
     * Before blocking, the raw socket MUST be handed to [register]; if that returns
     * false the resolver is shutting down and the connector must close it and
     * return null. Registering lets [DotResolver.close] abort an in-progress
     * connect/handshake instead of waiting out its timeout.
     *
     * Returns null on any connect/handshake failure (or an exhausted [budget]).
     */
    fun open(
        endpoint: DotResolver.DotEndpoint,
        budget: DotBudget,
        register: (Closeable) -> Boolean,
    ): DotConnection?
}

/**
 * The time a single query may still spend in DoT. Hands out per-step socket
 * timeouts capped at both the step's own limit and what is left of the overall
 * deadline, and remembers whether any timeout was cut short by the deadline: a
 * failure under a truncated timeout says little about the endpoint's health, so
 * [DotResolver] doesn't back the endpoint off for it.
 */
internal class DotBudget(private val nowMs: () -> Long, private val deadlineMs: Long) {

    @Volatile var truncated = false
        private set

    fun expired(): Boolean = nowMs() >= deadlineMs

    fun remainingMs(): Long = deadlineMs - nowMs()

    /** The wall-clock backstop cut a step short (see DotDeadlineTimer). */
    fun markTruncated() {
        truncated = true
    }

    /**
     * The timeout to use for a step whose own limit is [capMs], or 0 when the
     * deadline has passed. Callers must treat 0 as "give up", never pass it to a
     * socket (where 0 means *infinite*).
     */
    fun timeoutMs(capMs: Int): Int {
        val remaining = deadlineMs - nowMs()
        if (remaining <= 0) {
            truncated = true
            return 0
        }
        if (remaining < capMs) {
            truncated = true
            return remaining.toInt()
        }
        return capMs
    }
}

/**
 * Real transport: TCP to the endpoint IP (routed around the VPN via [protect]),
 * wrapped in TLS with SNI and certificate hostname verification bound to
 * [DotResolver.DotEndpoint.tlsHost].
 *
 * @param protect routes a socket around the VPN tunnel before it connects, so DoT
 *   traffic isn't itself captured by the tunnel (VpnService.protect).
 */
internal class TlsDotConnector(private val protect: (Socket) -> Boolean) : DotConnector {

    override fun open(
        endpoint: DotResolver.DotEndpoint,
        budget: DotBudget,
        register: (Closeable) -> Boolean,
    ): DotConnection? {
        val plain = Socket()
        if (!register(plain)) {
            closeQuietly(plain)
            return null
        }
        val ssl: SSLSocket
        try {
            // Route around the tunnel BEFORE connecting so the SYN doesn't loop
            // back through our own VPN.
            protect(plain)
            val connectTimeout = budget.timeoutMs(CONNECT_TIMEOUT_MS)
            if (connectTimeout <= 0) throw java.io.IOException("DoT deadline exceeded")
            plain.connect(InetSocketAddress(endpoint.ip, endpoint.port), connectTimeout)
            val readTimeout = budget.timeoutMs(READ_TIMEOUT_MS)
            if (readTimeout <= 0) throw java.io.IOException("DoT deadline exceeded")
            plain.soTimeout = readTimeout
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, endpoint.tlsHost, endpoint.port, /* autoClose = */ true) as SSLSocket
            // Bound handshake/read on the wrapping socket too, not just the delegate.
            ssl.soTimeout = readTimeout
        } catch (e: Exception) {
            closeQuietly(plain)
            return null
        }
        try {
            // "HTTPS" endpoint identification makes the handshake verify the cert's
            // hostname against tlsHost. Without it, a wrapped SSLSocket checks the
            // chain but NOT the hostname — a valid cert for the wrong host would
            // pass. (API 24+; minSdk is 26.) createSocket(..., tlsHost, ...) sets SNI.
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
        } catch (e: Exception) {
            closeQuietly(ssl) // autoClose tears down the underlying plain socket too
            return null
        }
        return SslDotConnection(ssl)
    }

    private class SslDotConnection(private val ssl: SSLSocket) : DotConnection {
        override val input: InputStream = ssl.inputStream
        override val output: OutputStream = ssl.outputStream
        override fun setReadTimeout(ms: Int) {
            ssl.soTimeout = ms
        }
        override fun close() = ssl.close()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 2500
        const val READ_TIMEOUT_MS = 2500
    }
}

internal fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (e: Exception) {
        // Best-effort close; nothing actionable on failure.
    }
}
