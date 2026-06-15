package com.calyptra.app.safesearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Manages DNS-level SafeSearch enforcement and YouTube Restricted Mode.
 * Redirects search engine domains to their forced-safe endpoints by returning
 * the SafeSearch VIP IPs instead of normal resolution.
 */
class SafeSearchManager {

    @Volatile var safeSearchEnabled: Boolean = true
    @Volatile var youtubeRestrictLevel: String = "strict" // "off", "moderate", "strict"

    // Hardcoded fallback IPs (stable Google VIPs).
    private val GOOGLE_SAFESEARCH_FALLBACK = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
    private val BING_SAFESEARCH_FALLBACK = byteArrayOf(204.toByte(), 79.toByte(), 197.toByte(), 220.toByte())
    private val YOUTUBE_STRICT_FALLBACK = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
    private val YOUTUBE_MODERATE_FALLBACK = byteArrayOf(216.toByte(), 239.toByte(), 38, 119)

    // Resolved IPs (updated at startup, fall back to hardcoded).
    private var googleIp = GOOGLE_SAFESEARCH_FALLBACK
    private var bingIp = BING_SAFESEARCH_FALLBACK
    private var duckduckgoIp: ByteArray? = null // No stable VIP; must resolve dynamically.
    private var youtubeStrictIp = YOUTUBE_STRICT_FALLBACK
    private var youtubeModerateIp = YOUTUBE_MODERATE_FALLBACK

    companion object {
        private const val QUERY_TYPE_AAAA = 28
    }

    // Google country-specific search domains (~189 TLDs).
    private val googleDomains: Set<String> = setOf(
        "google.com",
        "google.ad",
        "google.ae",
        "google.com.af",
        "google.com.ag",
        "google.al",
        "google.am",
        "google.co.ao",
        "google.com.ar",
        "google.as",
        "google.at",
        "google.com.au",
        "google.az",
        "google.ba",
        "google.com.bd",
        "google.be",
        "google.bf",
        "google.bg",
        "google.com.bh",
        "google.bi",
        "google.bj",
        "google.com.bn",
        "google.com.bo",
        "google.com.br",
        "google.bs",
        "google.bt",
        "google.co.bw",
        "google.by",
        "google.com.bz",
        "google.ca",
        "google.cd",
        "google.cf",
        "google.cg",
        "google.ch",
        "google.ci",
        "google.co.ck",
        "google.cl",
        "google.cm",
        "google.cn",
        "google.com.co",
        "google.co.cr",
        "google.com.cu",
        "google.cv",
        "google.com.cy",
        "google.cz",
        "google.de",
        "google.dj",
        "google.dk",
        "google.dm",
        "google.com.do",
        "google.dz",
        "google.com.ec",
        "google.ee",
        "google.com.eg",
        "google.es",
        "google.com.et",
        "google.fi",
        "google.com.fj",
        "google.fm",
        "google.fr",
        "google.ga",
        "google.ge",
        "google.gg",
        "google.com.gh",
        "google.com.gi",
        "google.gl",
        "google.gm",
        "google.gr",
        "google.com.gt",
        "google.gy",
        "google.com.hk",
        "google.hn",
        "google.hr",
        "google.ht",
        "google.hu",
        "google.co.id",
        "google.ie",
        "google.co.il",
        "google.im",
        "google.co.in",
        "google.iq",
        "google.is",
        "google.it",
        "google.je",
        "google.com.jm",
        "google.jo",
        "google.co.jp",
        "google.co.ke",
        "google.com.kh",
        "google.ki",
        "google.kg",
        "google.co.kr",
        "google.com.kw",
        "google.kz",
        "google.la",
        "google.com.lb",
        "google.li",
        "google.lk",
        "google.co.ls",
        "google.lt",
        "google.lu",
        "google.lv",
        "google.com.ly",
        "google.co.ma",
        "google.md",
        "google.me",
        "google.mg",
        "google.mk",
        "google.ml",
        "google.com.mm",
        "google.mn",
        "google.com.mt",
        "google.mu",
        "google.mv",
        "google.mw",
        "google.com.mx",
        "google.com.my",
        "google.co.mz",
        "google.com.na",
        "google.com.ng",
        "google.com.ni",
        "google.ne",
        "google.nl",
        "google.no",
        "google.com.np",
        "google.nr",
        "google.nu",
        "google.co.nz",
        "google.com.om",
        "google.com.pa",
        "google.com.pe",
        "google.com.pg",
        "google.com.ph",
        "google.com.pk",
        "google.pl",
        "google.pn",
        "google.com.pr",
        "google.ps",
        "google.pt",
        "google.com.py",
        "google.com.qa",
        "google.ro",
        "google.ru",
        "google.rw",
        "google.com.sa",
        "google.com.sb",
        "google.sc",
        "google.se",
        "google.com.sg",
        "google.sh",
        "google.si",
        "google.sk",
        "google.com.sl",
        "google.sn",
        "google.so",
        "google.sm",
        "google.sr",
        "google.st",
        "google.com.sv",
        "google.td",
        "google.tg",
        "google.co.th",
        "google.com.tj",
        "google.tl",
        "google.tm",
        "google.tn",
        "google.to",
        "google.com.tr",
        "google.tt",
        "google.com.tw",
        "google.co.tz",
        "google.com.ua",
        "google.co.ug",
        "google.co.uk",
        "google.com.uy",
        "google.co.uz",
        "google.com.vc",
        "google.co.ve",
        "google.co.vi",
        "google.com.vn",
        "google.vu",
        "google.ws",
        "google.rs",
        "google.co.za",
        "google.co.zm",
        "google.co.zw",
        "google.cat"
    )

    private val bingDomains = setOf("bing.com", "www.bing.com")
    private val duckduckgoDomains = setOf("duckduckgo.com", "www.duckduckgo.com")

    private val youtubeDomains = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtubei.googleapis.com", "youtube.googleapis.com",
        "www.youtube-nocookie.com", "music.youtube.com"
    )

    /**
     * Returns redirect IP for SafeSearch/YouTube domains, or null if no redirect needed.
     * For AAAA queries (queryType=28) on redirected domains, returns empty array
     * to signal a NODATA response (forces IPv4 fallback).
     */
    fun getRedirectIp(domain: String, queryType: Int): ByteArray? {
        val lower = domain.lowercase()

        if (safeSearchEnabled) {
            if (isGoogleDomain(lower)) {
                return if (queryType == QUERY_TYPE_AAAA) byteArrayOf() else googleIp
            }
            if (bingDomains.contains(lower)) {
                return if (queryType == QUERY_TYPE_AAAA) byteArrayOf() else bingIp
            }
            if (duckduckgoDomains.contains(lower) && duckduckgoIp != null) {
                return if (queryType == QUERY_TYPE_AAAA) byteArrayOf() else duckduckgoIp
            }
        }

        if (youtubeRestrictLevel != "off" && isYouTubeDomain(lower)) {
            val ip = if (youtubeRestrictLevel == "strict") youtubeStrictIp else youtubeModerateIp
            return if (queryType == QUERY_TYPE_AAAA) byteArrayOf() else ip
        }

        return null
    }

    /** Resolve SafeSearch CNAMEs via upstream DNS. Called on VPN start. */
    suspend fun resolveEndpoints() = withContext(Dispatchers.IO) {
        googleIp = resolveOrFallback("forcesafesearch.google.com", GOOGLE_SAFESEARCH_FALLBACK)
        bingIp = resolveOrFallback("strict.bing.com", BING_SAFESEARCH_FALLBACK)
        duckduckgoIp = resolveOrNull("safe.duckduckgo.com")
        youtubeStrictIp = resolveOrFallback("restrict.youtube.com", YOUTUBE_STRICT_FALLBACK)
        youtubeModerateIp = resolveOrFallback("restrictmoderate.youtube.com", YOUTUBE_MODERATE_FALLBACK)
    }

    private fun resolveOrFallback(hostname: String, fallback: ByteArray): ByteArray {
        return try {
            InetAddress.getByName(hostname).address
        } catch (e: Exception) {
            fallback
        }
    }

    private fun resolveOrNull(hostname: String): ByteArray? {
        return try {
            InetAddress.getByName(hostname).address
        } catch (e: Exception) {
            null
        }
    }

    private fun isGoogleDomain(domain: String): Boolean {
        return googleDomains.contains(domain) ||
               (domain.startsWith("www.") && googleDomains.contains(domain.removePrefix("www.")))
    }

    private fun isYouTubeDomain(domain: String): Boolean {
        return youtubeDomains.contains(domain)
    }
}
