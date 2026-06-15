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
 * Order: redirect > category NXDOMAIN > ad blocklist > allow.
 */
object DnsPolicy {

    fun resolve(
        domain: String,
        queryType: Int,
        getRedirectIp: (String, Int) -> ByteArray?,
        isCategoryBlocked: (String) -> Boolean,
        isAdBlocked: (String) -> Boolean
    ): Verdict {
        val redirectIp = getRedirectIp(domain, queryType)
        if (redirectIp != null) {
            return if (redirectIp.isEmpty()) Verdict.Nodata else Verdict.Redirect(redirectIp)
        }
        if (isCategoryBlocked(domain)) return Verdict.BlockNxdomain
        if (isAdBlocked(domain)) return Verdict.BlockZeroIp
        return Verdict.Allow
    }
}
