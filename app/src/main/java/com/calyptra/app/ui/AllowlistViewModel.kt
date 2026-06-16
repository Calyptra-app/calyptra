package com.calyptra.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calyptra.app.CalyptraApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the per-domain "Allowed sites" screen — the false-positive escape hatch.
 * Unlike the app whitelist (Room-backed, per-app routing), this is a simple
 * domain set in DataStore that feeds DnsPolicy's first-check allowlist. Changes
 * apply live: AdBlockVpnService collects the same flow and rebuilds its matcher
 * with no VPN restart, so no reconfigure broadcast is needed here.
 */
class AllowlistViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = (application as CalyptraApp).preferencesRepository

    /** Sorted for a stable list; the underlying pref is an unordered Set. */
    val domains: StateFlow<List<String>> = preferencesRepository.allowedDomains
        .map { set -> set.sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addDomain(domain: String) {
        val candidate = normalize(domain)
        if (!isValidDomain(candidate)) return
        viewModelScope.launch {
            preferencesRepository.addAllowedDomain(candidate)
        }
    }

    fun removeDomain(domain: String) {
        viewModelScope.launch {
            preferencesRepository.removeAllowedDomain(domain)
        }
    }

    companion object {
        private fun normalize(raw: String): String =
            raw.trim().lowercase().removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").substringBefore('/').trim()

        /** Loose hostname check: at least one dot, valid label chars, no scheme.
         *  We validate the typed input at the boundary — the rest of the app
         *  trusts the stored set. */
        private val HOSTNAME = Regex(
            "^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$"
        )

        fun isValidDomain(candidate: String): Boolean =
            candidate.isNotEmpty() && candidate.length <= 253 && HOSTNAME.matches(candidate)
    }
}
