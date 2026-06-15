# Blocklist System - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - Domain matching and list management.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/calyptra/app/blocklist/BlocklistManager.kt` | Load, merge, and provide domain sets |
| `app/src/main/java/com/calyptra/app/blocklist/DomainMatcher.kt` | Subdomain-aware blocking decisions |
| `app/src/main/java/com/calyptra/app/blocklist/BlocklistUpdater.kt` | HTTPS fetch of remote list |
| `app/src/main/java/com/calyptra/app/blocklist/CategoryBlockManager.kt` | Social category matching (SOC-L1/L2) |
| `app/src/main/res/raw/default_blocklist.txt` | Bundled blocklist (~4,541 curated domains) |
| `app/src/main/res/raw/category_<key>.txt` | Per-category social domain lists (8 files) |

---

## BLK-L1: Bundled Blocklist Loading

**File**: `BlocklistManager.kt` - `initialize()` / `loadBundledBlocklist()`

**Flow:**
1. On VPN start, `blocklistManager.initialize()` is called (also called by `saveUpdate()` after download).
2. `loadBundledBlocklist()` reads `R.raw.default_blocklist` via `BufferedReader`.
3. `loadCachedUpdates()` reads `context.filesDir/updated_blocklist.txt` if it exists, capped at `MAX_REMOTE_DOMAINS`.
4. Both sets are merged: `domains = bundled + cached`.
5. `matcher = DomainMatcher(domains)` created from merged set.

**Parsing rules** (both bundled and cached):
- Skip blank lines.
- Skip lines starting with `#`.
- `trim().lowercase()` each domain.

**Bundled list**: ~4,541 curated high-confidence domains (intersection of Hagezi Light + StevenBlack + manual mobile game ad SDK domains).

---

## BLK-L2: Domain Matching Algorithm

**File**: `DomainMatcher.kt`

```kotlin
class DomainMatcher(private val blocklist: Set<String>) {
    fun isBlocked(domain: String): Boolean {
        var current = domain.lowercase()
        if (blocklist.contains(current)) return true
        while (current.contains('.')) {
            if (blocklist.contains(current)) return true
            current = current.substringAfter('.')
        }
        return blocklist.contains(current)  // final single-label check
    }
}
```

**Algorithm**: Walk up the domain hierarchy checking each level against the HashSet.

**Examples:**
| Input | Blocklist entry | Match | Result |
|-------|----------------|-------|--------|
| `ads.doubleclick.net` | `doubleclick.net` | Parent match | BLOCKED |
| `a.b.c.doubleclick.net` | `doubleclick.net` | Ancestor match | BLOCKED |
| `doubleclick.net` | `doubleclick.net` | Exact match | BLOCKED |
| `google.com` | (not in list) | No match | ALLOWED |
| `notdoubleclick.net` | `doubleclick.net` | Different suffix | ALLOWED |

**Performance**: O(k) where k = number of dots (typically 2-4). HashSet lookups O(1).

---

## BLK-L3: Remote Blocklist Fetching

**File**: `BlocklistUpdater.kt`

```kotlin
class BlocklistUpdater {
    private val DEFAULT_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt"

    fun fetch(urlStr: String = DEFAULT_URL): Set<String> {
        val connection = URL(urlStr).openConnection() as HttpsURLConnection  // enforces HTTPS by type
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Calyptra/1.0.0 (Android)")
        // Returns Set<String> on HTTP 200, throws on non-200
    }
}
```

**Remote source**: Hagezi Light (~118k domains). Chosen over Multi Normal (~303k) to stay within 50 MB memory budget.
**Timeout**: 10s connect, 10s read.
**Error handling**: Throws `Exception` on non-200. Caller (`BlocklistUpdateWorker`) handles retry.

---

## BLK-L4: Bundled + Remote Merge

**File**: `BlocklistManager.kt` - `saveUpdate()` / `initialize()`

**Save flow** (called by `BlocklistUpdateWorker` after fetch):
1. `saveUpdate(newDomains)` caps to `MAX_REMOTE_DOMAINS = 200_000` if needed.
2. Writes to `context.filesDir/updated_blocklist.txt` (one domain per line).
3. Calls `initialize()` to reload merged set immediately.

**Merge logic** (in `initialize()`):
```
Final blocklist = bundled domains UNION cached domains
```
- Bundled list always included (cannot be removed).
- Downloaded list adds to it (never replaces).
- Duplicates eliminated by Set data structure.
- Memory at 200k cap: ~18 MB HashSet.

---

## BLK-L5: Game Ads Toggle

**File**: `BlocklistManager.kt`

```kotlin
@Volatile var allowGameAds: Boolean = false

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

fun isBlocked(domain: String): Boolean {
    if (allowGameAds && isGameAdDomain(domain)) return false
    return matcher?.isBlocked(domain) ?: false
}

private fun isGameAdDomain(domain: String): Boolean {
    val lower = domain.lowercase()
    return gameAdDomains.any { lower == it || lower.endsWith(".$it") }
}
```

**Reactive update**: `AdBlockVpnService.startVpn()` launches a coroutine collecting `preferencesRepository.gameAdsAllowed` and writing to `blocklistManager.allowGameAds`. Takes effect immediately without VPN restart. `@Volatile` ensures visibility from the packet-loop coroutine.

**Default**: `false` (block all — strictest protection).

---

## SOC-L1/L2: Social Media Category Blocking

**File**: `CategoryBlockManager.kt`

**Categories (SOC-L1):**
```kotlin
enum class SocialCategory(val key: String) {
    TIKTOK("tiktok"), INSTAGRAM("instagram"), SNAPCHAT("snapchat"),
    FACEBOOK("facebook"), TWITTER("twitter"), REDDIT("reddit"),
    DISCORD("discord"), TWITCH("twitch");

    companion object {
        fun fromKey(key: String): SocialCategory?
        fun fromKeys(keys: Set<String>): Set<SocialCategory>  // drops unknown keys silently
    }
}
```

- Domain lists live in `res/raw/category_<key>.txt` (same parsing rules as BLK-L1: skip blanks/`#`, `trim().lowercase()`). Loaded via `CategoryBlockManager.fromResources(context)` on `Dispatchers.IO`.
- **YouTube is deliberately not a category** — it is controlled via Restricted Mode (VPN-L3.6), never hard-blocked.
- **`whatsapp.com` / `whatsapp.net` are deliberately excluded** from the facebook set (family messaging).

**Matching (SOC-L2):**
```kotlin
class CategoryBlockManager(private val loadCategory: suspend (SocialCategory) -> Set<String>) {
    @Volatile private var matcher: DomainMatcher? = null

    suspend fun setEnabledCategories(categories: Set<SocialCategory>) {
        matcher = if (categories.isEmpty()) null
                  else DomainMatcher(categories.flatMap { loadCategory(it) }.toSet())
    }

    fun isCategoryBlocked(domain: String): Boolean = matcher?.isBlocked(domain) ?: false
}
```

- Enabled sets are merged into **one immutable `DomainMatcher`, rebuilt on toggle** — sets are tiny (< 500 domains total), so rebuilds are cheap and need **no VPN restart** (FR-11.4). `@Volatile` ensures visibility from the packet-loop coroutine (same pattern as BLK-L5).
- Reactive wiring: `AdBlockVpnService.startVpn()` collects `preferencesRepository.blockedCategories` (key set) → `setEnabledCategories(SocialCategory.fromKeys(keys))`.
- Category hits answer **NXDOMAIN** via `Verdict.BlockNxdomain` (apps fail fast), unlike ad blocks which answer `0.0.0.0`. Checked after SafeSearch redirect, before the ad blocklist — see the Verdict Pipeline in [VPN-SERVICE.md](VPN-SERVICE.md).

---

## Test Coverage

| Test | File | Covers |
|------|------|--------|
| Exact domain match | `test/.../blocklist/DomainMatcherTest.kt` | BLK-L2 |
| Subdomain match | `test/.../blocklist/DomainMatcherTest.kt` | BLK-L2 |
| Case insensitivity | `test/.../blocklist/DomainMatcherTest.kt` | BLK-L2 |
| Non-matching domains | `test/.../blocklist/DomainMatcherTest.kt` | BLK-L2 |

## Known Gaps

| ID | Issue | Impact |
|----|-------|--------|
| CHK022 | No hash/signature verification of remote list | Supply chain risk |
