# VPN Service - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - Implementation details for the VPN engine.

## Files

| File | Purpose | Lines (approx) |
|------|---------|----------------|
| `app/src/main/java/com/calyptra/app/vpn/AdBlockVpnService.kt` | Core VPN service, packet loop, TUN setup | ~450 |
| `app/src/main/java/com/calyptra/app/vpn/DnsInterceptor.kt` | DNS packet parsing, response construction | ~185 |
| `app/src/main/java/com/calyptra/app/vpn/DnsPolicy.kt` | `Verdict` sealed interface + policy ordering | ~45 |
| `app/src/main/java/com/calyptra/app/vpn/VpnController.kt` | Singleton VPN state broadcaster (sealed `VpnState`) | ~75 |
| `app/src/main/java/com/calyptra/app/vpn/NetworkEnvironmentMonitor.kt` | Private DNS detection + network callbacks | ~60 |
| `app/src/main/java/com/calyptra/app/vpn/RestartGuard.kt` | Crash-loop breaker (see [BACKGROUND.md](BACKGROUND.md) PWR-L1) | ~35 |
| `app/src/main/java/com/calyptra/app/safesearch/SafeSearchManager.kt` | SafeSearch/YouTube DNS redirect mappings | ~295 |

---

## VPN-L1: VPN Tunnel Establishment

**File**: `AdBlockVpnService.kt` - `onStartCommand()` / `startVpn()`

**What happens:**
1. User taps "Enable Protection" → `MainViewModel.setProtectionEnabled(true)` → pref saved.
2. `MainActivity` calls `VpnService.prepare(context)` to get permission intent.
3. Android shows system VPN consent dialog.
4. On approval, `VpnController.startVpn(context)` calls `context.startForegroundService(intent)`.
5. `onStartCommand()` calls `startForeground()` then `startVpn()`.
6. Service checks preference `protectionEnabled`; stops self if `false`.
7. Calls `blocklistManager.initialize()` and `safeSearchManager.resolveEndpoints()`.
8. Reactively subscribes to four preferences via collected `Flow`s:
   - `gameAdsAllowed` → `blocklistManager.allowGameAds`
   - `safeSearchEnabled` → `safeSearchManager.safeSearchEnabled`
   - `youtubeRestrictLevel` → `safeSearchManager.youtubeRestrictLevel`
   - `blockedCategories` → `categoryBlockManager.setEnabledCategories(SocialCategory.fromKeys(keys))` (SOC-L2)
9. Builds TUN interface:

```
Builder configuration:
  - Address:    10.0.0.2/32
  - DNS server: 10.0.0.1  (sinkhole — all DNS queries come to us)
  - Route:      10.0.0.1/32  (DNS-ONLY intercept — not a full tunnel)
  - Session:    "Calyptra"
  - setBlocking(false)
```

10. Reads whitelisted packages via `database.whitelistDao().getAllPackageNames()` (returns `List<String>`).
11. Calls `builder.addDisallowedApplication(pkg)` for each whitelisted package.
12. `builder.establish()` returns `ParcelFileDescriptor`.
13. On success: `restartGuard.reset()` (PWR-L1), `VpnController.updateAlwaysOn(isAlwaysOn)` on API 29+ (PWR-L3), and `networkMonitor.startWatching { VpnWatchdogScheduler.checkNow(...) }` (PWR-L4).
14. Starts `processPackets()` coroutine on `Dispatchers.IO`.
15. On `establish()` returning null or any exception → `handleStartFailure(cause)` (see PWR-L1 in [BACKGROUND.md](BACKGROUND.md)): retry via `VpnWatchdogScheduler.checkNow()` if `restartGuard.shouldRetryAfterFailure()`, otherwise alert notification (id 3) and `stopSelf()`.

**Key detail**: Route `10.0.0.1/32` (not `0.0.0.0/0`) — only DNS traffic is intercepted, not all device traffic.

---

## VPN-L2: DNS Query Interception

**File**: `AdBlockVpnService.kt` - `processPackets()`

**Packet loop:**
```kotlin
while (currentCoroutineContext().isActive) {
    val length = withContext(Dispatchers.IO) { inputStream.read(buffer.array()) }
    if (length <= 0) { delay(1); buffer.clear(); continue }

    val packetData = buffer.array().copyOf(length)
    val packetBuffer = ByteBuffer.wrap(packetData)

    val version = (packetBuffer.get(0).toInt() shr 4) and 0xF
    val protocol = packetBuffer.get(9).toInt()

    if (version == 4 && protocol == 17) {  // IPv4 + UDP
        val ihl = packetBuffer.get(0).toInt() and 0xF
        val headerLength = ihl * 4
        val dstPort = ((packetBuffer.get(headerLength + 2)...) ...)
        if (dstPort == 53) {
            scope?.launch { handleDnsRequest(packetBuffer, headerLength, outputStream) }
        }
    }
    buffer.clear()
}
```

**Key details:**
- IPv4 + UDP + port 53 check is inline in the loop (not in DnsInterceptor).
- Each DNS request is dispatched as a separate coroutine (`scope?.launch`).
- Buffer is `ByteBuffer.allocate(32767)`.

**File**: `DnsInterceptor.kt` - constructor + `processDnsPacket()`

`DnsInterceptor` now takes a **single `resolveVerdict` callback** (see "Verdict Pipeline" below):
```kotlin
DnsInterceptor { domain, queryType ->
    DnsPolicy.resolve(
        domain = domain,
        queryType = queryType,
        getRedirectIp = { d, t -> safeSearchManager.getRedirectIp(d, t) },
        isCategoryBlocked = { d -> categoryBlockManager.isCategoryBlocked(d) },
        isAdBlocked = { d -> blocklistManager.isBlocked(d) }
    )
}
```

**DNS parsing steps** (inside `processDnsPacket(packet: ByteBuffer)`):
1. Extract byte array from buffer.
2. Validate minimum size ≥ 12 bytes.
3. Read QDCOUNT from bytes 4-5; return null if 0.
4. Walk label-encoded name starting at offset 12 (handle compression pointer → return null).
5. Extract `queryType` from bytes after name end.
6. Check DoH canary set → NXDOMAIN if matched (not overridable; precedes the policy).
7. Call `resolveVerdict(domain, queryType)` and map the `Verdict` to a response (or null for `Allow` — caller forwards to upstream).

---

## Verdict Pipeline

**Files**: `DnsPolicy.kt`, `DnsInterceptor.kt`

All DNS policy decisions are ordered in one place:

```kotlin
sealed interface Verdict {
    data object Allow : Verdict
    data object BlockZeroIp : Verdict      // ad/tracker sinkhole → A 0.0.0.0 (VPN-L3)
    data object BlockNxdomain : Verdict    // parental category block (SOC-L2)
    data object Nodata : Verdict           // AAAA on redirected domain (VPN-L3.6)
    class Redirect(val ip: ByteArray) : Verdict  // SafeSearch/YouTube (VPN-L3.6)
}
```

**Decision order:**
1. **DoH canary** (VPN-L3.5) — stays *inside* `DnsInterceptor`, checked before `DnsPolicy`; cannot be overridden by policy.
2. **Redirect** — `getRedirectIp()` non-null → `Redirect(ip)`, or `Nodata` if the array is empty (AAAA).
3. **Category NXDOMAIN** — `isCategoryBlocked()` → `BlockNxdomain` (SOC-L2).
4. **Ad blocklist** — `isAdBlocked()` → `BlockZeroIp` (0.0.0.0).
5. **Allow** — forwarded upstream (VPN-L4).

`DnsPolicy.resolve()` is a pure function (no Android deps); `DnsInterceptor` maps each `Verdict` to the matching response constructor (`constructBlockedResponse` / `constructNxdomainResponse` / `constructNodataResponse` / `constructRedirectResponse`).

---

## VPN-L3: Blocked Domain Response

**File**: `DnsInterceptor.kt` - `constructBlockedResponse()`

```
Response structure:
  Header:
    - ID: copied from request (bytes 0-1)
    - Flags: 0x8180 (QR=1, AA=1, RA=1, RCODE=0 no error)
    - QDCOUNT = 1, ANCOUNT = 1, NSCOUNT = 0, ARCOUNT = 0
  Question: copied from request (bytes 12..questionEnd)
  Answer (A record):
    - Name: 0xC00C (pointer to offset 12)
    - Type: 1 (A), Class: 1 (IN)
    - TTL: 60 seconds
    - RDLength: 4
    - RDATA: 0.0.0.0
```

**Result**: The requesting app receives `0.0.0.0`, blocking the connection. Stats counter incremented in `handleDnsRequest()` after receiving non-null response.

---

## VPN-L3.5: DoH Provider NXDOMAIN

**File**: `DnsInterceptor.kt` - companion object + `constructNxdomainResponse()`

Checked **before** SafeSearch and blocklist. Returns NXDOMAIN to prevent browsers from enabling DNS-over-HTTPS, which would bypass VPN-based DNS interception.

**Blocked domains (8):**
```kotlin
val DOH_CANARY_DOMAINS = setOf(
    "use-application-dns.net",   // Firefox DoH canary
    "dns.google",                // Chrome Secure DNS
    "dns.google.com",
    "cloudflare-dns.com",
    "mozilla.cloudflare-dns.com",
    "doh.opendns.com",
    "dns.quad9.net",
    "doh.cleanbrowsing.org"
)
```

**NXDOMAIN response flags**: `0x8183` (QR=1, AA=1, RA=1, RCODE=3), ANCOUNT=0.

---

## VPN-L3.6: SafeSearch & YouTube DNS Redirect

**Files**: `DnsInterceptor.kt`, `safesearch/SafeSearchManager.kt`

Checked **after** DoH canary, **before** blocklist.

**`getRedirectIp(domain: String, queryType: Int): ByteArray?`** return values:
- `null` → no redirect (continue to blocklist check)
- non-empty `ByteArray` (4 bytes) → A query; return redirect response with that IP
- empty `ByteArray` (`byteArrayOf()`) → AAAA query on redirected domain; return NODATA

**SafeSearch domains covered:**
- **Google**: ~139 country-specific TLDs + `www.` prefix variants. Endpoint: `forcesafesearch.google.com`. Fallback IP: `216.239.38.120`.
- **Bing**: `bing.com`, `www.bing.com`. Endpoint: `strict.bing.com`. Fallback IP: `204.79.197.220`.
- **DuckDuckGo**: `duckduckgo.com`, `www.duckduckgo.com`. Endpoint: `safe.duckduckgo.com`. **No fallback IP** (null if resolve fails → no redirect).

**YouTube Restricted Mode domains:**
```kotlin
"youtube.com", "www.youtube.com", "m.youtube.com",
"youtubei.googleapis.com", "youtube.googleapis.com",
"www.youtube-nocookie.com", "music.youtube.com"
```
- Strict: redirect to `restrict.youtube.com`. Fallback: `216.239.38.120`.
- Moderate: redirect to `restrictmoderate.youtube.com`. Fallback: `216.239.38.119`.

**NODATA response flags**: `0x8180` (RCODE=0 no error), ANCOUNT=0. Forces IPv4 fallback.

**Redirect response**: same structure as blocked response but with SafeSearch IP instead of `0.0.0.0`. TTL: 300s.

**IP resolution**: `SafeSearchManager.resolveEndpoints()` called at VPN startup via `InetAddress.getByName()`. Hardcoded fallbacks used if resolution fails.

**Preference reactivity**: `AdBlockVpnService.startVpn()` launches three collect coroutines that write directly to `@Volatile` fields on `SafeSearchManager`. Takes effect immediately without VPN restart.

---

## VPN-L4: Upstream DNS Forwarding

**File**: `AdBlockVpnService.kt` - `queryUpstream()` / `queryDnsServer()`

When `processDnsPacket()` returns null (allowed):

1. Extract DNS payload from `dnsPacket.remaining()` bytes.
2. `queryUpstream(dnsData)` tries primary then fallback:
   - Primary: `185.228.168.168` (CleanBrowsing Family)
   - Fallback: `1.1.1.3` (Cloudflare Families)
3. `queryDnsServer()`:
   - Creates `DatagramSocket`, calls `protect(socket)` to bypass VPN loop.
   - Sets `soTimeout = 2500` ms.
   - Sends DNS query, receives response into `ByteArray(4096)`.
   - Returns null on any exception.
4. Wraps upstream response in IP+UDP headers via `constructIpUdpResponse()`.
5. Writes to `outputStream` under `synchronized(outputStream)`.

**Error handling**: Null from primary → try fallback. Null from both → query dropped silently (app retries naturally).

---

## VPN-L5: VPN State Management

**File**: `VpnController.kt`

```kotlin
sealed interface VpnState {
    data object Stopped : VpnState
    data object Running : VpnState
    data object Revoked : VpnState   // another app took the VPN (CFT-L1)
}

object VpnController {
    val state: StateFlow<VpnState>            // full state
    val isRunning: StateFlow<Boolean>         // derived: state == Running
    val alwaysOnActive: StateFlow<Boolean>    // system Always-on VPN targets us (PWR-L3)

    fun updateState(running: Boolean)   // Stopped/Running; Revoked survives updateState(false)
    fun notifyRevoked()                 // → Revoked (called from onRevoke)
    fun acknowledgeRevoked()            // Revoked → Stopped (explicit user toggle)
    fun updateAlwaysOn(active: Boolean)
    fun startVpn(context: Context)      // startForegroundService
    fun stopVpn(context: Context)       // ACTION_STOP intent
}
```

**State transitions:**
- `onStartCommand()` (after checking prefs) → `VpnController.updateState(true)` → `Running`
- `stopVpn()` / `onDestroy()` → `VpnController.updateState(false)` → `Stopped` **unless current state is `Revoked`** (Revoked must survive the cleanup path)
- `onRevoke()` → `VpnController.notifyRevoked()` → `Revoked` (CFT-L1)
- `MainViewModel.setProtectionEnabled(false)` → `VpnController.acknowledgeRevoked()` consumes a pending `Revoked`
- `MainViewModel` collects both `VpnController.isRunning` and `VpnController.state` in the combined `uiState` flow.

**Stop action**: `ACTION_STOP = "com.calyptra.app.vpn.STOP"`. `onStartCommand` checks `intent?.action == ACTION_STOP` and calls `stopVpn()`.

---

## CFT-L1: VPN Revocation Handling

**File**: `AdBlockVpnService.kt` - `onRevoke()`

Android allows only one active VPN. When another app's `VpnService.establish()` succeeds, the OS calls our `onRevoke()`:

```kotlin
override fun onRevoke() {
    VpnController.notifyRevoked()   // BEFORE cleanup, so it survives stopVpn()'s updateState(false)
    postRevokedNotification()       // alert channel, notification id 2 (CFT-L2)
    stopVpn()
}
```

**Key details:**
- `VpnState.Revoked` is distinct from `Stopped` so the UI (CFT-L3 conflict banner) and watchdog can tell a conflict from a normal user-initiated stop.
- `VpnController.updateState(false)` (called by the cleanup path) does **not** overwrite `Revoked` — only `acknowledgeRevoked()` (explicit user toggle in `MainViewModel.setProtectionEnabled`) clears it.
- Parent is alerted via a high-importance notification on channel `calyptra_alerts` (CFT-L2).

**Notification ids / channels:**

| ID | Purpose | Channel |
|----|---------|---------|
| 1 | Foreground service ("protection active") | `calyptra_channel` (LOW) |
| 2 | VPN revoked alert (CFT-L2) | `calyptra_alerts` (HIGH) |
| 3 | Watchdog alert: permission lost / crash-loop give-up | `calyptra_alerts` (HIGH) |

---

## CFT-L4: Network Environment Monitor

**File**: `NetworkEnvironmentMonitor.kt`

Detects environment-level DNS bypasses. **Detection only — never changes OS settings.**

```kotlin
private fun isPrivateDnsStrict(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val network = connectivityManager.activeNetwork ?: return false
    val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
    return linkProperties.privateDnsServerName != null
}
```

- Exposes `privateDnsActive: StateFlow<Boolean>`, refreshed via `refresh()` on app resume (`MainViewModel.onResumed`) and on every network change.
- **Only strict mode counts** (explicit hostname → `privateDnsServerName != null`). Automatic/opportunistic mode falls back to plain DNS under our VPN and is not a bypass.
- `startWatching(onNetworkChange)` registers a default network callback (`onAvailable`/`onLost` → `refresh()` + callback). The service uses it to trigger `VpnWatchdogScheduler.checkNow()` on Wi-Fi/mobile handover (PWR-L4). `stopWatching()` is called from `stopVpn()`.
- Strict Private DNS surfaces in the UI as `ConflictState.PRIVATE_DNS` (CFT-L3, see [UI-LAYER.md](UI-LAYER.md)).

---

## VPN-L6: Foreground Service Notification

**File**: `AdBlockVpnService.kt` - `createNotification()`

- Channel ID: `"calyptra_channel"` (defined in `CalyptraApp.CHANNEL_ID`)
- Channel name: "Calyptra Protection", importance: `IMPORTANCE_LOW`
- Notification content title: value of `R.string.protection_enabled`
- Notification content text: `"Calyptra is blocking ads in the background"`
- Small icon: `R.mipmap.ic_launcher`
- Tap action: Opens `MainActivity` via `PendingIntent.FLAG_IMMUTABLE`
- Notification ID: `1`

**Android 14 requirement**: `foregroundServiceType="specialUse"` in manifest.

---

## WL-L1: Per-App VPN Exclusion

**File**: `AdBlockVpnService.kt` - `startVpn()`

```kotlin
val whitelistedPackages = (applicationContext as CalyptraApp).database.whitelistDao().getAllPackageNames()
for (pkg in whitelistedPackages) {
    try { builder.addDisallowedApplication(pkg) }
    catch (e: Exception) { Log.e(TAG, "Failed to whitelist $pkg", e) }
}
```

- `getAllPackageNames()` returns `List<String>` (package names only, blocking `suspend`).
- Invalid packages (uninstalled apps) caught per-item and logged; rest of list proceeds.
- **Caveat**: Requires VPN restart to pick up whitelist changes.

---

## Test Coverage

| Test | File | Covers |
|------|------|--------|
| DNS packet parsing | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L2, VPN-L3 |
| Blocked response construction | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3 |
| DoH canary NXDOMAIN | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3.5 |
| SafeSearch redirect (A record) | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3.6 |
| YouTube redirect (A record) | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3.6 |
| NODATA for AAAA on redirect | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3.6 |
| No redirect when disabled | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L3.6 |
| SafeSearchManager domain matching | `test/.../safesearch/SafeSearchManagerTest.kt` | VPN-L3.6 |
| Allowed domain (null return) | `test/.../vpn/DnsInterceptorTest.kt` | VPN-L4 |

## Known Gaps

| ID | Issue | Impact |
|----|-------|--------|
| CHK003 | ~~No VPN restart after crash~~ Addressed by watchdog (PWR-L2) + RestartGuard (PWR-L1) | Resolved |
| CHK004 | ~~No VPN conflict detection~~ Addressed by CFT-L1/CFT-L3 | Resolved |
| CHK005 | Always-on VPN setting not tested | May conflict |
| CHK007 | DNS over TCP not handled | Some queries may fail |
| CHK009 | Malformed DNS packets not validated (caught by try/catch, returns null) | Partial mitigation only |
| CHK024 | DNS leak if VPN fails open | Privacy gap |
