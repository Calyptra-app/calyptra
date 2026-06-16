package com.calyptra.app.blocklist

import android.content.Context
import com.calyptra.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class BlocklistManager(private val context: Context) {

    companion object {
        const val MAX_REMOTE_DOMAINS = 200_000
        const val MAX_THREAT_DOMAINS = 250_000
    }

    @Volatile var allowGameAds: Boolean = false

    private var domains: Set<String> = emptySet()
    // Published across threads: rebuilt on a WorkManager/IO thread (initialize()
    // via saveUpdate()) but read by the packet loop in isBlocked(). @Volatile
    // guarantees the reader sees freshly downloaded rules without a VPN restart.
    @Volatile private var matcher: DomainMatcher? = null

    // Threat (malware/phishing/scam) matcher. Kept SEPARATE from the ad matcher
    // so the verdict can differ: threats return NXDOMAIN, ads sinkhole to 0.0.0.0.
    private var threatDomains: Set<String> = emptySet()
    // Same cross-thread publication as the ad matcher: written by the weekly
    // worker (initialize() via saveThreatUpdate()), read by the packet loop in
    // isThreatBlocked(). @Volatile makes new threat rules visible without restart.
    @Volatile private var threatMatcher: DomainMatcher? = null

    private val gameAdDomains = setOf(
        // Unity Ads
        "unityads.unity3d.com", "ads.unity3d.com", "adserver.unityads.unity3d.com",
        // AppLovin
        "applovin.com", "d.applovin.com", "rt.applovin.com", "ms.applovin.com",
        // Vungle
        "vungle.com", "ads.vungle.com", "cdn-lb.vungle.com", "api.vungle.com",
        // ironSource
        "supersonicads.com", "outcome-ssp.supersonicads.com", "init.supersonicads.com",
        // Chartboost
        "chartboost.com", "ads.chartboost.com", "live.chartboost.com",
        // Meta Audience Network
        "an.facebook.com",
    )

    suspend fun initialize() {
        val bundled = loadBundledBlocklist()
        val cached = loadCachedUpdates()
        domains = bundled + cached
        matcher = DomainMatcher(domains)

        val threatBundled = loadBundledThreatlist()
        val threatCached = loadCachedThreatUpdates()
        threatDomains = threatBundled + threatCached
        threatMatcher = DomainMatcher(threatDomains)
    }

    fun isBlocked(domain: String): Boolean {
        if (allowGameAds && isGameAdDomain(domain)) return false
        return matcher?.isBlocked(domain) ?: false
    }

    /** Always-on malware/phishing/scam check (separate from the ad matcher).
     *  Not affected by allowGameAds — security is never relaxed. */
    fun isThreatBlocked(domain: String): Boolean {
        return threatMatcher?.isBlocked(domain) ?: false
    }

    private fun isGameAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        return gameAdDomains.any { lower == it || lower.endsWith(".$it") }
    }

    suspend fun loadBundledBlocklist(): Set<String> = withContext(Dispatchers.IO) {
        val inputStream = context.resources.openRawResource(R.raw.default_blocklist)
        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.trim().lowercase() }
                .toSet()
        }
    }
    
    suspend fun loadCachedUpdates(): Set<String> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "updated_blocklist.txt")
        if (file.exists()) {
            file.useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim().lowercase() }
                    .take(MAX_REMOTE_DOMAINS)
                    .toSet()
            }
        } else {
            emptySet()
        }
    }
    
    suspend fun saveUpdate(newDomains: Set<String>) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "updated_blocklist.txt")
        val capped = if (newDomains.size > MAX_REMOTE_DOMAINS) {
            newDomains.take(MAX_REMOTE_DOMAINS).toSet()
        } else {
            newDomains
        }
        file.bufferedWriter().use { writer ->
            capped.forEach { domain ->
                writer.write(domain)
                writer.newLine()
            }
        }
        initialize() // Reload
    }

    suspend fun loadBundledThreatlist(): Set<String> = withContext(Dispatchers.IO) {
        val inputStream = context.resources.openRawResource(R.raw.threat_seed)
        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.trim().lowercase() }
                .toSet()
        }
    }

    suspend fun loadCachedThreatUpdates(): Set<String> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "updated_threatlist.txt")
        if (file.exists()) {
            file.useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim().lowercase() }
                    .take(MAX_THREAT_DOMAINS)
                    .toSet()
            }
        } else {
            emptySet()
        }
    }

    suspend fun saveThreatUpdate(newDomains: Set<String>) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "updated_threatlist.txt")
        val capped = if (newDomains.size > MAX_THREAT_DOMAINS) {
            newDomains.take(MAX_THREAT_DOMAINS).toSet()
        } else {
            newDomains
        }
        file.bufferedWriter().use { writer ->
            capped.forEach { domain ->
                writer.write(domain)
                writer.newLine()
            }
        }
        initialize() // Reload
    }
}
