# Calyptra - Architecture Overview

> **Progressive Disclosure Level 1** - How everything connects. Drill into [tech/](tech/) for implementation details.

## System Diagram

```
┌──────────────────────────────────────────────────────────┐
│                     Android Device                       │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │                  Calyptra App                   │ │
│  │                                                     │ │
│  │  ┌──────────┐    ┌──────────────┐   ┌───────────┐  │ │
│  │  │ UI Layer │───▶│  ViewModel   │──▶│Repository │  │ │
│  │  │ (Compose)│◀───│  (StateFlow) │◀──│  Layer    │  │ │
│  │  └──────────┘    └──────────────┘   └─────┬─────┘  │ │
│  │       │                                    │        │ │
│  │       │ VPN Permission                     │        │ │
│  │       ▼                                    ▼        │ │
│  │  ┌──────────────────┐    ┌──────────────────────┐   │ │
│  │  │  VPN Service     │    │    Data Sources       │   │ │
│  │  │  (Foreground)    │    │  ┌────────┐ ┌──────┐ │   │ │
│  │  │  ┌────────────┐  │    │  │  Room  │ │Data- │ │   │ │
│  │  │  │DNS         │  │    │  │  DB    │ │Store │ │   │ │
│  │  │  │Interceptor │  │    │  └────────┘ └──────┘ │   │ │
│  │  │  └──┬───┬─────┘  │    └──────────────────────┘   │ │
│  │  │     │   │        │                               │ │
│  │  │     │  ┌▼──────────┐                              │ │
│  │  │     │  │SafeSearch  │  DNS redirect for           │ │
│  │  │     │  │Manager     │  search engines + YouTube   │ │
│  │  │     │  └────────────┘                              │ │
│  │  │     ▼               │                               │ │
│  │  │  ┌────────────┐  │    ┌──────────────────────┐   │ │
│  │  │  │Blocklist   │  │    │  Background Workers   │   │ │
│  │  │  │Manager     │◀─┼────│  (WorkManager)        │   │ │
│  │  │  │+Matcher    │  │    │  BlocklistUpdateWorker │   │ │
│  │  │  └────────────┘  │    └──────────────────────┘   │ │
│  │  └──────────────────┘                               │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌──────────────────┐                                    │
│  │ TUN Interface    │  ← All device DNS traffic          │
│  │ 10.0.0.2/32      │                                    │
│  └──────────────────┘                                    │
└──────────────────────────────────────────────────────────┘
         │
         │ Allowed DNS queries only
         ▼
   ┌──────────────────┐     ┌──────────────┐
   │ 185.228.168.168  │────▶│  1.1.1.3     │
   │ CleanBrowsing    │fail │  Cloudflare  │
   │ Family (primary) │     │  Families    │
   └──────────────────┘     └──────────────┘
```

## Package Structure

```
com.calyptra.app/
├── CalyptraApp.kt              # Application class (manual DI container)
├── MainActivity.kt             # Single activity, hosts NavHost
├── vpn/                        # VPN engine (the core)
│   ├── AdBlockVpnService.kt    # Foreground VPN service
│   ├── DnsInterceptor.kt       # DNS packet parse + response construction
│   ├── DnsPolicy.kt            # Verdict sealed interface + policy ordering
│   ├── VpnController.kt        # Singleton VPN state broadcaster (sealed VpnState)
│   ├── NetworkEnvironmentMonitor.kt # Private DNS detection + network callbacks (CFT-L4)
│   └── RestartGuard.kt         # VPN crash-loop breaker (PWR-L1)
├── safesearch/                 # SafeSearch & YouTube DNS redirect
│   └── SafeSearchManager.kt   # Domain→IP mapping, endpoint resolution
├── blocklist/                  # Domain matching subsystem
│   ├── BlocklistManager.kt     # Load & merge domain lists
│   ├── DomainMatcher.kt        # O(n) subdomain-walking matcher
│   ├── CategoryBlockManager.kt # Social category blocking (SOC-L1/L2)
│   └── BlocklistUpdater.kt     # HTTPS fetcher for remote list
├── security/                   # Parental PIN (PIN-L1..L4)
│   ├── PinHasher.kt            # PBKDF2 + constant-time compare
│   ├── LockoutPolicy.kt        # Exponential lockout (pure)
│   └── PinManager.kt           # PinStore interface, verify, grace session
├── system/
│   └── PowerStatusProvider.kt  # Battery-exemption status + settings intents (PWR-L3)
├── data/                       # Persistence layer
│   ├── AppDatabase.kt          # Room DB (v2, 3 entities, MIGRATION_1_2)
│   ├── BlockedStat.kt          # Entity: daily block count
│   ├── WhitelistedApp.kt       # Entity: per-app exclusion
│   ├── ProtectionEvent.kt      # Entity: protection state transitions (TML-L1)
│   ├── StatsDao.kt             # Stats queries
│   ├── WhitelistDao.kt         # Whitelist queries
│   ├── ProtectionEventDao.kt   # Event log queries + insertAndPrune (TML-L1)
│   ├── StatsRepository.kt      # Stats business logic (Flow)
│   ├── ProtectionEventRepository.kt # Event log writer, watchdog dedup (TML-L2)
│   ├── RetentionPolicy.kt      # Pure retention rule: newest 500 ∪ last 30 days (FR-13.6)
│   ├── PreferencesRepository.kt# DataStore wrapper
│   └── Converters.kt           # Room type converters
├── ui/                         # Jetpack Compose UI
│   ├── home/
│   │   └── KidHomeScreen.kt    # Kid world: hero toggle + stats (F12)
│   ├── MainViewModel.kt        # Shared state management
│   ├── PinGate.kt              # GatedAction + PinGateReducer (PIN-L3)
│   ├── ConflictState.kt        # ConflictState + reducer (CFT-L3)
│   ├── WhitelistScreen.kt      # App whitelist UI
│   ├── WhitelistViewModel.kt   # Whitelist state
│   ├── pin/
│   │   └── PinPromptDialog.kt  # PIN setup/challenge dialog
│   ├── settings/
│   │   ├── ParentSettingsScreen.kt # Parent world, PIN-gated entry (F12)
│   │   ├── CategorySection.kt  # Social category toggles (SOC-L4)
│   │   ├── ProtectionTimelineScreen.kt # F13 tamper log UI (TML-L3)
│   │   ├── ProtectionEventMapper.kt    # Pure events → day groups (TML-L3)
│   │   └── TimelineViewModel.kt        # Timeline state
│   ├── components/
│   │   ├── ProtectionToggle.kt # Hero circular shield toggle (F12)
│   │   ├── ConflictBanner.kt   # Amber conflict warning (CFT-L3)
│   │   └── StatsCard.kt        # Today/total stats card
│   └── theme/
│       ├── Theme.kt            # Material3 + dynamic color
│       ├── Color.kt            # Shield green/red/blue/yellow
│       └── Type.kt             # Typography scale
├── worker/
│   ├── BlocklistUpdateWorker.kt# Weekly blocklist refresh
│   └── VpnWatchdogWorker.kt    # VPN continuity check + scheduler (PWR-L2)
└── receiver/
    └── BootReceiver.kt         # Auto-start on boot
```

## Data Flow: DNS Query Lifecycle

```
1. Any app makes DNS query
       │
2. Android routes through TUN interface (VPN active)
       │
3. AdBlockVpnService reads packet from TUN FileDescriptor
       │
4. DnsInterceptor.processPacket(packetBytes)
       │
       ├── Parse IP header (20 bytes)
       ├── Parse UDP header (8 bytes)
       ├── Parse DNS question section
       ├── Extract queried domain name
       │
5. SafeSearchManager.getRedirectIp(domain, queryType)
       │
       ├─── REDIRECT ──▶ Construct DNS response with SafeSearch/YouTube IP
       │                  (or NODATA for AAAA queries)
       │                  Write response to TUN
       │
       └─── null ──▶ Continue to step 5b

5b. CategoryBlockManager.isCategoryBlocked(domain)   (SOC-L2)
       │
       ├─── YES ──▶ NXDOMAIN response (Verdict.BlockNxdomain), write to TUN
       │
       └─── NO ──▶ Continue to step 6

6. DomainMatcher.isBlocked(domain)
       │
       ├─── YES (blocked) ──▶ Construct DNS response with 0.0.0.0
       │                      Write response to TUN
       │                      Increment StatsDao.incrementCount(today)
       │
       └─── NO (allowed) ──▶ Forward query to CleanBrowsing Family (185.228.168.168:53)
                              Fallback to Cloudflare Families (1.1.1.3:53) on failure
                              Read response (2.5s timeout per server)
                              Write response to TUN
```

## Dependency Injection (Manual)

```kotlin
// CalyptraApp.kt provides lazy singletons
class CalyptraApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val preferencesRepository by lazy { PreferencesRepository(this) }  // takes Context, not DataStore
    val statsRepository by lazy { StatsRepository(database.statsDao()) }
    val blocklistManager by lazy { BlocklistManager(this) }
    val categoryBlockManager by lazy { CategoryBlockManager.fromResources(this) }
    val safeSearchManager by lazy { SafeSearchManager() }
    val pinManager by lazy { PinManager(preferencesRepository) }   // PreferencesRepository implements PinStore
    val networkMonitor by lazy { NetworkEnvironmentMonitor(this) }
    val powerStatusProvider by lazy { PowerStatusProvider(this) }
    val protectionEventRepository by lazy {                        // TML-L2; runs on a private
        ProtectionEventRepository(database.protectionEventDao(), ioScope)  // app-lifetime IO scope
    }
}

// Access pattern from activities/services:
val app = applicationContext as CalyptraApp
val repo = app.statsRepository
```

`CalyptraApp.onCreate()` also runs the **watchdog lockstep collector**: an app-scope coroutine collects `protectionEnabled` and schedules/cancels `VpnWatchdogScheduler` accordingly (PWR-L2).

Notification channels: `"calyptra_channel"` (IMPORTANCE_LOW, foreground service) and `"calyptra_alerts"` (IMPORTANCE_HIGH, revoked/watchdog alerts).

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Ad blocking mechanism | VpnService (DNS-only via `10.0.0.1/32` route) | No root needed, privacy-preserving, minimal traffic intercept |
| UI framework | Jetpack Compose | Modern, declarative, less boilerplate |
| DI framework | Manual (no Hilt/Koin) | MVP simplicity, <10 dependencies |
| State management | Single `MainUiState` via `combine` on 7 flows | All UI state in one place, avoids partial recompositions |
| Local storage | Room + DataStore | Room for relational data, DataStore for prefs |
| Background work | WorkManager | Battery-friendly, survives reboots |
| Navigation | Navigation Compose | 2 screens, no deep linking needed |
| Upstream DNS | CleanBrowsing Family (185.228.168.168) + Cloudflare Families (1.1.1.3) fallback | Family-safe content filtering, blocks adult content |
| DoH canary | NXDOMAIN for 8 known DoH provider domains | Prevents browser DoH bypass |
| Blocklist format | Plain text (one domain/line) | Simple to parse, small size |
| Remote blocklist | Hagezi Light (~118k domains) | Good coverage within 50 MB memory budget |
| Domain count cap | 200,000 max remote domains | Prevents OOM from oversized lists |
| Game ads toggle | `@Volatile allowGameAds` on BlocklistManager | Immediate effect without VPN restart |
| SafeSearch enforcement | DNS redirect to SafeSearch VIPs | Deterministic local control, independent of upstream provider |
| YouTube Restricted Mode | DNS redirect to restrict.youtube.com | Off/Moderate/Strict levels via DNS |
| SafeSearch IP resolution | Resolve at startup + hardcoded fallback | IPs are stable but can change; offline fallback needed |
| AAAA handling for redirects | Return NODATA (ANCOUNT=0, flags=0x8180) | Forces IPv4 fallback; avoids IPv6 redirect complexity |
| Boot auto-start | BootReceiver checks `prepare()==null` AND the `protection_enabled` pref (via `goAsync`) | Starts the service only when intended, so `ENABLED_BOOT` is logged exactly on real auto-starts (TML-L2) |
| Tamper visibility | Local append-only `protection_events` log behind the PIN (F13) | Remote notification banned by Principle I; data wipe is itself a loud signal (PIN re-prompt + empty timeline) |
| Event log retention | Prune on insert: keep newest 500 ∪ last 30 days | ~20 KB worst case; 30-day window never punctured so off-durations stay honest |

## Thread Model

| Component | Thread/Dispatcher | Reason |
|-----------|-------------------|--------|
| VPN packet loop | `Dispatchers.IO` | Blocking I/O on TUN fd |
| DNS forwarding | `Dispatchers.IO` | Network I/O to upstream |
| Room queries | `Dispatchers.IO` (via Flow) | Database I/O |
| DataStore | `Dispatchers.IO` (internal) | File I/O |
| Compose UI | Main thread | UI rendering |
| WorkManager | Background thread | System-managed |

## Testing Strategy

| Layer | Type | Location | Runner |
|-------|------|----------|--------|
| DomainMatcher | Unit | `test/.../blocklist/` | JUnit 4 |
| DnsInterceptor | Unit | `test/.../vpn/` | JUnit 4 |
| RetentionPolicy / ProtectionEventRepository | Unit | `test/.../data/` | JUnit 4 |
| ProtectionEventMapper | Unit | `test/.../ui/settings/` | JUnit 4 |
| StatsDao / ProtectionEventDao | Instrumented | `androidTest/.../data/` | AndroidJUnit4 |
| Room migration (v1 → v2) | Instrumented | `androidTest/.../data/Migration1To2Test.kt` | MigrationTestHelper |
| UI (future) | Compose | - | ComposeTestRule |
