package com.calyptra.app.blocklist

import android.content.Context
import com.calyptra.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Blockable social platforms (F11). YouTube is deliberately absent — it is
 *  controlled via Restricted Mode (VPN-L3.6), never hard-blocked. */
enum class SocialCategory(val key: String) {
    TIKTOK("tiktok"),
    INSTAGRAM("instagram"),
    SNAPCHAT("snapchat"),
    FACEBOOK("facebook"),
    TWITTER("twitter"),
    REDDIT("reddit"),
    DISCORD("discord"),
    TWITCH("twitch");

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

    suspend fun setEnabledCategories(categories: Set<SocialCategory>) {
        matcher = if (categories.isEmpty()) {
            null
        } else {
            DomainMatcher(categories.flatMap { loadCategory(it) }.toSet())
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
