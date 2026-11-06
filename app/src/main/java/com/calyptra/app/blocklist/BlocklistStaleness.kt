package com.calyptra.app.blocklist

import java.util.concurrent.TimeUnit

/**
 * Decides when to run a blocklist update right away instead of waiting for the
 * weekly periodic job. The periodic work uses KEEP, so after an upgrade it keeps
 * its old schedule: a device whose updates had been failing (e.g. the 404 in
 * #85) would otherwise wait up to another week on a stale list.
 */
object BlocklistStaleness {

    /** A day of slack over the weekly period, so a normal schedule never triggers it. */
    val MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(8)

    /** [lastUpdateMs] is 0 when no remote update ever succeeded. */
    fun needsCatchUp(lastUpdateMs: Long, nowMs: Long): Boolean =
        lastUpdateMs <= 0L || nowMs - lastUpdateMs > MAX_AGE_MS
}
