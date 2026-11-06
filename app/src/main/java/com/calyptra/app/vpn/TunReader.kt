package com.calyptra.app.vpn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor

/**
 * Readiness-based reader for the non-blocking tun descriptor (#72).
 *
 * The old loop called read(), got nothing, slept 1 ms and tried again, which is
 * ~1000 wakeups a second while the device is idle. This instead sleeps in
 * poll() until the tunnel has a packet, at zero CPU.
 *
 * poll() can't be interrupted by coroutine cancellation, and closing a
 * descriptor another thread is polling does not wake it on Linux. So the
 * reader also polls a private pipe; [wake] writes a byte to it and [read]
 * returns [WOKEN]. Callers wake the reader BEFORE closing the tunnel.
 *
 * All tunnel I/O goes through the [FileDescriptor] object, never a raw int:
 * once the tunnel's ParcelFileDescriptor is closed, that object is invalidated,
 * so a stale reader gets EBADF instead of reading a recycled descriptor number
 * that now belongs to a replacement tunnel.
 */
internal class TunReader(private val tun: FileDescriptor) : Closeable {

    private val lock = Any()
    private var closed = false
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor

    init {
        val pipe = Os.pipe()
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    /** Makes a pending or future [read] return [WOKEN]. Safe after [close]. */
    fun wake() {
        synchronized(lock) {
            if (closed) return
            try {
                Os.write(wakeWrite, WAKE_BYTE, 0, 1)
            } catch (e: ErrnoException) {
                // Pipe full means a wake is already pending; anything else means
                // we're tearing down. Either way there is nothing more to signal.
            }
        }
    }

    /**
     * Blocks until one packet is read into [buffer] and returns its length.
     * Returns [WOKEN] after [wake], or [ENDED] when the tunnel hung up, hit
     * EOF, or failed (e.g. EBADF because it was closed underneath us).
     */
    fun read(buffer: ByteArray): Int {
        while (true) {
            try {
                val n = Os.read(tun, buffer, 0, buffer.size)
                return if (n > 0) n else ENDED // 0 = EOF: the tunnel is gone
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) return ENDED
            }
            // Nothing queued: sleep until the tunnel or the wake pipe is readable.
            val fds = arrayOf(pollFd(tun), pollFd(wakeRead))
            try {
                Os.poll(fds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return ENDED
            }
            if (fds[1].revents.toInt() != 0) return WOKEN
            if (fds[0].revents.toInt() and FAILURE_EVENTS != 0) return ENDED
            // POLLIN on the tunnel: loop back and read it.
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { Os.close(wakeRead) } catch (_: ErrnoException) {}
            try { Os.close(wakeWrite) } catch (_: ErrnoException) {}
        }
    }

    private fun pollFd(fd: FileDescriptor) = StructPollfd().apply {
        this.fd = fd
        events = OsConstants.POLLIN.toShort()
    }

    companion object {
        const val WOKEN = -1
        const val ENDED = -2
        private val WAKE_BYTE = byteArrayOf(1)
        private val FAILURE_EVENTS =
            OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
    }
}
