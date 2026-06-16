package com.calyptra.app.blocklist

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class BlocklistUpdater {

    // Default URL pointing to the repo main branch
    private val DEFAULT_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt"

    // HaGeZi Threat Intelligence Feeds — Mini (malware/phishing/scam). Plain
    // one-domain-per-line list (GPL-3.0). The historical `domains/tif.mini.txt`
    // path is gone upstream; the current plain-domains export is published as
    // `wildcard/tif.mini-onlydomains.txt`.
    private val THREAT_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt"

    /** Fetches the always-on threat (malware/phishing) blocklist. */
    fun fetchThreat(): Set<String> = fetch(THREAT_URL)

    fun fetch(urlStr: String = DEFAULT_URL): Set<String> {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Calyptra/1.3.0 (Android)")

        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                return reader.useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { it.trim().lowercase() }
                        .toSet()
                }
            } else {
                throw Exception("Failed to fetch blocklist: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
}
