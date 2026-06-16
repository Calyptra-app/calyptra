package com.calyptra.app.vpn

sealed interface Verdict {
    data object Allow : Verdict

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
 * Order: allowlist > redirect > threat NXDOMAIN > category NXDOMAIN >
 * ad blocklist > allow.
 *
 * The parent domain allowlist is the very first check: an allowlisted domain is
 * the false-positive escape hatch, so it short-circuits to Allow before any
 * threat/redirect/category/ad rule can touch it. (Allowlisting a domain you
 * also redirect for SafeSearch un-does the redirect — that's the parent's call.)
 *
 * After the allowlist, the always-on malware/phishing threat check is the first
 * blocking check (after the SafeSearch redirect): a domain on the threat list is
 * dead, period (NXDOMAIN), regardless of any ad-list or category-list overlap.
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
        if (isDomainAllowed(domain)) return Verdict.Allow
        val redirectIp = getRedirectIp(domain, queryType)
        if (redirectIp != null) {
            return if (redirectIp.isEmpty()) Verdict.Nodata else Verdict.Redirect(redirectIp)
        }
        if (isThreatBlocked(domain)) return Verdict.BlockNxdomain
        if (isCategoryBlocked(domain)) return Verdict.BlockNxdomain
        if (isAdBlocked(domain)) return Verdict.BlockZeroIp
        return Verdict.Allow
    }
}
