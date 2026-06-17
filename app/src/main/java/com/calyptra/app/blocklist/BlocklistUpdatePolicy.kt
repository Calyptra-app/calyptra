package com.calyptra.app.blocklist

/**
 * Plausibility gate for downloaded blocklist / threat-feed updates.
 *
 * An update overwrites the cached remote list with whatever was downloaded. A
 * truncated download or a hijacked endpoint returning a near-empty body would
 * otherwise silently shrink protection until the next good update. We refuse to
 * replace a healthy cache with an implausibly small one: an update must clear an
 * absolute floor and retain at least half of the current cached entry count.
 *
 * Pure and stateless so the decision can be unit-tested without Android.
 */
object BlocklistUpdatePolicy {

    /**
     * An update with fewer than this many domains is never accepted. The remote
     * feeds (HaGeZi Light, TIF Mini) each carry tens of thousands of entries, so
     * this only trips on truncated, hijacked, or empty responses.
     */
    const val ABSOLUTE_FLOOR = 1_000

    /**
     * A new update must retain at least this fraction of the current cached
     * count. Catches a feed that collapses (e.g. 70k -> 5k) while tolerating the
     * normal day-to-day fluctuation of a maintained list. Skipped when there is
     * no usable cache yet (first run).
     */
    const val MIN_RETENTION_RATIO = 0.5

    /**
     * True when an update of [newCount] domains may replace a cache currently
     * holding [currentCount] domains.
     */
    fun isPlausibleUpdate(newCount: Int, currentCount: Int): Boolean {
        if (newCount < ABSOLUTE_FLOOR) return false
        if (currentCount >= ABSOLUTE_FLOOR && newCount < currentCount * MIN_RETENTION_RATIO) return false
        return true
    }
}
