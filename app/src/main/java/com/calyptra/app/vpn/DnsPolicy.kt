package com.calyptra.app.vpn

sealed interface Verdict {
    data object Allow : Verdict

    /** Parent-allowlisted: forward to the malware-only resolver instead of the
     *  family filter, so the allowlist can also override an upstream
     *  CleanBrowsing block (e.g. cinobo.com). */
    data object AllowUnfiltered : Verdict

    /** Ad/tracker sinkhole: answer A record 0.0.0.0 (VPN-L3). */
    data object BlockZeroIp : Verdict

    /** Parental category block: answer NXDOMAIN so apps fail fast (SOC-L2). */
    data object BlockNxdomain : Verdict

    /** Empty answer with RCODE=0 — forces IPv4 fallback on AAAA queries for
     *  redirected domains (VPN-L3.6). */
    data object Nodata : Verdict

    /** SafeSearch / YouTube Restricted Mode CNAME-style redirect (VPN-L3.6). */
    class Redirect(val ip: ByteArray) : Verdict
}

/**
 * Single decision point ordering all DNS policies. The DoH canary check
 * (VPN-L3.5) stays inside DnsInterceptor and precedes this policy.
 *
 * Order: redirect > threat NXDOMAIN > allowlist > category NXDOMAIN >
 * ad blocklist > allow.
 *
 * The parent allowlist overrides CONTENT filtering only: Calyptra's category
 * and ad lists, and (via AllowUnfiltered) the family-filter resolver upstream.
 * It never overrides safety:
 *  - SafeSearch / YouTube Restricted redirects still apply, so allowlisting
 *    google.com can't switch SafeSearch off;
 *  - the always-on malware/phishing threat list still answers NXDOMAIN, so a
 *    mistyped or compromised allowlisted domain stays dead.
 */
object DnsPolicy {

    fun resolve(
        domain: String,
        queryType: Int,
        isDomainAllowed: (String) -> Boolean,
        getRedirectIp: (String, Int) -> ByteArray?,
        isThreatBlocked: (String) -> Boolean,
        isCategoryBlocked: (String) -> Boolean,
        isAdBlocked: (String) -> Boolean
    ): Verdict {
        val redirectIp = getRedirectIp(domain, queryType)
        if (redirectIp != null) {
            return if (redirectIp.isEmpty()) Verdict.Nodata else Verdict.Redirect(redirectIp)
        }
        if (isThreatBlocked(domain)) return Verdict.BlockNxdomain
        if (isDomainAllowed(domain)) return Verdict.AllowUnfiltered
        if (isCategoryBlocked(domain)) return Verdict.BlockNxdomain
        if (isAdBlocked(domain)) return Verdict.BlockZeroIp
        return Verdict.Allow
    }
}
