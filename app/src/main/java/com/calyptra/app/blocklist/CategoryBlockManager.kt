package com.calyptra.app.blocklist

import android.content.Context
import com.calyptra.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/** Parent-blockable content categories (F11). Most members are social
 *  platforms; NSFW is an adult/pornography category bundled from HaGeZi.
 *  YouTube is deliberately absent — it is controlled via Restricted Mode
 *  (VPN-L3.6), never hard-blocked. */
enum class SocialCategory(val key: String) {
    TIKTOK("tiktok"),
    INSTAGRAM("instagram"),
    SNAPCHAT("snapchat"),
    FACEBOOK("facebook"),
    TWITTER("twitter"),
    REDDIT("reddit"),
    DISCORD("discord"),
    TWITCH("twitch"),

    /** Adult/NSFW content (HaGeZi NSFW, GPL-3.0). Default OFF, PIN-gated.
     *  Rendered separately from the social grid as an "Adult content" toggle. */
    NSFW("nsfw");

    companion object {
        fun fromKey(key: String): SocialCategory? = entries.firstOrNull { it.key == key }

        /** Parses a persisted key set, silently dropping unknown values. */
        fun fromKeys(keys: Set<String>): Set<SocialCategory> =
            keys.mapNotNull(::fromKey).toSet()
    }
}

/**
 * Matches domains of parent-blocked social categories (SOC-L2). Enabled sets
 * are merged into one immutable DomainMatcher, rebuilt on toggle — the sets
 * are tiny (< 500 domains total), so rebuilds are cheap and need no VPN
 * restart (FR-11.4). Category hits answer NXDOMAIN via Verdict.BlockNxdomain.
 */
class CategoryBlockManager(
    private val loadCategory: suspend (SocialCategory) -> Set<String>
) {

    @Volatile
    private var matcher: DomainMatcher? = null

    /** Serializes rebuilds so two slow reloads can't run concurrently, and stamps
     *  each request with a monotonically increasing token. Loading the 96k-line
     *  NSFW set is slow, so a removal ({nsfw}) issued after an add ({reddit,nsfw})
     *  could otherwise finish first and be overwritten by the older rebuild —
     *  leaving a disabled category still blocked in the live matcher. The mutex
     *  serializes the work and the token guarantees last-write-wins. */
    private val rebuildMutex = Mutex()
    private val requestSeq = AtomicLong(0L)

    suspend fun setEnabledCategories(categories: Set<SocialCategory>) {
        // Claim a token BEFORE awaiting the lock, so ordering reflects call order.
        val token = requestSeq.incrementAndGet()
        rebuildMutex.withLock {
            // A newer request already won; drop this stale rebuild entirely so it
            // can't load + install an older set over the current one.
            if (token < requestSeq.get()) return
            val next = if (categories.isEmpty()) {
                null
            } else {
                DomainMatcher(categories.flatMap { loadCategory(it) }.toSet())
            }
            // Re-check after the (slow) load: if a newer request was claimed while
            // we were loading, discard our now-stale result instead of installing.
            if (token < requestSeq.get()) return
            matcher = next
        }
    }

    fun isCategoryBlocked(domain: String): Boolean = matcher?.isBlocked(domain) ?: false

    companion object {
        private val CATEGORY_RES = mapOf(
            SocialCategory.TIKTOK to R.raw.category_tiktok,
            SocialCategory.INSTAGRAM to R.raw.category_instagram,
            SocialCategory.SNAPCHAT to R.raw.category_snapchat,
            SocialCategory.FACEBOOK to R.raw.category_facebook,
            SocialCategory.TWITTER to R.raw.category_twitter,
            SocialCategory.REDDIT to R.raw.category_reddit,
            SocialCategory.DISCORD to R.raw.category_discord,
            SocialCategory.TWITCH to R.raw.category_twitch,
            SocialCategory.NSFW to R.raw.nsfw,
        )

        fun fromResources(context: Context): CategoryBlockManager =
            CategoryBlockManager { category ->
                withContext(Dispatchers.IO) {
                    val resId = CATEGORY_RES.getValue(category)
                    val reader = BufferedReader(
                        InputStreamReader(context.resources.openRawResource(resId))
                    )
                    reader.useLines { lines ->
                        lines.filter { it.isNotBlank() && !it.startsWith("#") }
                            .map { it.trim().lowercase() }
                            .toSet()
                    }
                }
            }
    }
}
