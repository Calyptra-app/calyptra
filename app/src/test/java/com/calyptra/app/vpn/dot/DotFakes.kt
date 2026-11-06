package com.calyptra.app.vpn.dot

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Manually advanced clock. */
internal class FakeClock(start: Long = 1_000_000L) {
    private val now = AtomicLong(start)
    fun now(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

/** A minimal valid DNS query for example.com A, with transaction id [id]. */
internal fun dnsQuery(id: Int = 0x1234): ByteArray {
    val qname = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
    val header = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, // id, RD
        0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // QDCOUNT=1
    )
    return header + qname + byteArrayOf(0x00, 0x01, 0x00, 0x01)
}

/** The answer a well-behaved resolver would send: the query with QR set. */
internal fun dnsAnswerFor(query: ByteArray): ByteArray =
    query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }

/**
 * Behaviour of one fake endpoint:
 * - [Mode.ANSWER]: opens instantly and answers every query.
 * - [Mode.REFUSE]: open fails instantly (connection refused).
 * - [Mode.DROP]: open "hangs" for the full connect timeout it was given, then fails
 *   (a silently dropped port 853), advancing the fake clock accordingly.
 * - [Mode.SILENT]: opens, but reads time out after the read timeout it was given.
 * - [Mode.BLOCK]: open blocks for real until its registered socket is closed.
 * - [Mode.THROW]: open throws an unexpected RuntimeException.
 */
internal class FakeConnector(private val clock: FakeClock, vararg modes: Mode) : DotConnector {

    enum class Mode { ANSWER, REFUSE, DROP, SILENT, BLOCK, THROW }

    val modes: Array<Mode> = arrayOf(*modes)
    val opens = Array(modes.size) { AtomicInteger() }
    val connectTimeouts = Array(modes.size) { mutableListOf<Int>() }
    val blockedOpens = AtomicInteger()
    val connections: MutableList<FakeConnection> = java.util.Collections.synchronizedList(mutableListOf())

    private val endpointsByIp = mutableMapOf<String, Int>()

    fun endpoints(): List<DotResolver.DotEndpoint> = modes.indices.map { i ->
        DotResolver.DotEndpoint("10.0.0.$i", "dot.example").also { endpointsByIp[it.ip] = i }
    }

    override fun open(
        endpoint: DotResolver.DotEndpoint,
        budget: DotBudget,
        register: (Closeable) -> Boolean,
    ): DotConnection? {
        val i = endpointsByIp.getValue(endpoint.ip)
        opens[i].incrementAndGet()
        val timeout = budget.timeoutMs(TlsDotConnector.CONNECT_TIMEOUT_MS)
        synchronized(connectTimeouts) { connectTimeouts[i].add(timeout) }
        val released = CountDownLatch(1)
        if (!register(Closeable { released.countDown() })) return null
        return when (modes[i]) {
            Mode.ANSWER -> FakeConnection(clock, silent = false).also { connections.add(it) }
            Mode.SILENT -> FakeConnection(clock, silent = true).also { connections.add(it) }
            Mode.REFUSE -> null
            Mode.DROP -> { clock.advance(timeout.toLong()); null }
            Mode.THROW -> throw IllegalStateException("boom")
            Mode.BLOCK -> {
                blockedOpens.incrementAndGet()
                released.await(10, TimeUnit.SECONDS)
                null
            }
        }
    }
}

/** Answers each framed query written to it, or (if [silent]) times out reads. */
internal class FakeConnection(private val clock: FakeClock, private val silent: Boolean) : DotConnection {
    @Volatile var closed = false
    private var readTimeout = 0
    private val written = ByteArrayOutputStream()
    private var pendingResponse = ByteArray(0)
    private var pos = 0

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = written.write(b)
        override fun flush() {
            val framed = written.toByteArray()
            written.reset()
            val query = framed.copyOfRange(2, framed.size)
            pendingResponse = DotFraming.frame(dnsAnswerFor(query))!!
            pos = 0
        }
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            if (closed) return -1
            if (silent || pos >= pendingResponse.size) {
                clock.advance(readTimeout.toLong())
                throw SocketTimeoutException("fake read timeout")
            }
            return pendingResponse[pos++].toInt() and 0xFF
        }
    }

    override fun setReadTimeout(ms: Int) {
        require(ms > 0) { "a 0 socket timeout means infinite" }
        readTimeout = ms
    }

    override fun close() { closed = true }
}
