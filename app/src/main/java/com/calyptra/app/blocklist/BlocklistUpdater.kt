package com.calyptra.app.blocklist

import com.calyptra.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class BlocklistUpdater {

    // HaGeZi Light, plain one-domain-per-line, each entry covering its
    // subdomains (DomainMatcher matches parents). Upstream removed the old
    // `domains/` tree; `domains/light.txt` 404'd, so updates silently failed
    // and devices stayed on the small bundled list. Same move as THREAT_URL.
    private val DEFAULT_URL = AD_LIST_URL

    // HaGeZi Threat Intelligence Feeds — Mini (malware/phishing/scam). Plain
    // one-domain-per-line list (GPL-3.0). The historical `domains/tif.mini.txt`
    // path is gone upstream; the current plain-domains export is published as
    // `wildcard/tif.mini-onlydomains.txt`.
    private val THREAT_URL = THREAT_LIST_URL

    /** Fetches the always-on threat (malware/phishing) blocklist. */
    fun fetchThreat(): Set<String> = fetch(THREAT_URL, BlocklistParser.THREAT_LIMITS)

    /**
     * Downloads and stream-parses a list. Throws (so the caller keeps its last
     * valid cache) on HTTP errors or when the response breaks any of [limits]:
     * the body is never fully buffered before the limits apply.
     */
    fun fetch(
        urlStr: String = DEFAULT_URL,
        limits: BlocklistParser.Limits = BlocklistParser.AD_LIMITS,
    ): Set<String> {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Calyptra/${BuildConfig.VERSION_NAME} (Android)")

        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                // Cheap early reject when the server declares an oversized body;
                // the streaming byte cap still applies when it lies or omits it.
                val declared = connection.contentLengthLong
                if (declared > limits.maxBytes) {
                    throw BlocklistLimitException("Blocklist response declares $declared bytes (max ${limits.maxBytes})")
                }
                return connection.inputStream.use { BlocklistParser.parse(it, limits) }
            } else {
                throw Exception("Failed to fetch blocklist: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal const val AD_LIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt"
        internal const val THREAT_LIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt"
    }
}
