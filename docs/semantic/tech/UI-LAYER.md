# UI Layer - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - Compose screens, ViewModels, components, theming.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/calyptra/app/MainActivity.kt` | Single activity, NavHost, hosts PinPromptDialog |
| `app/src/main/java/com/calyptra/app/ui/home/KidHomeScreen.kt` | Kid world: hero toggle + stats (F12) |
| `app/src/main/java/com/calyptra/app/ui/settings/ParentSettingsScreen.kt` | Parent world: all settings, PIN-gated entry (F12) |
| `app/src/main/java/com/calyptra/app/ui/MainViewModel.kt` | Shared state management (combined uiState) |
| `app/src/main/java/com/calyptra/app/ui/WhitelistScreen.kt` | Whitelist management screen |
| `app/src/main/java/com/calyptra/app/ui/WhitelistViewModel.kt` | Whitelist state management |
| `app/src/main/java/com/calyptra/app/ui/components/ProtectionToggle.kt` | Hero circular shield toggle with state animation |
| `app/src/main/java/com/calyptra/app/ui/components/StatsCard.kt` | `KidStatsRow`: stat pills with count-up |
| `app/src/main/java/com/calyptra/app/ui/components/ConflictBanner.kt` | Amber conflict warning (CFT-L3) |
| `app/src/main/java/com/calyptra/app/ui/PinGate.kt` | `GatedAction` + `PinGateReducer` (see [SECURITY.md](SECURITY.md)) |
| `app/src/main/java/com/calyptra/app/ui/ConflictState.kt` | `ConflictState` + reducer (CFT-L3) |
| `app/src/main/java/com/calyptra/app/ui/pin/PinPromptDialog.kt` | PIN setup/challenge dialog |
| `app/src/main/java/com/calyptra/app/ui/settings/CategorySection.kt` | Social category toggles (SOC-L4) |
| `app/src/main/java/com/calyptra/app/ui/settings/ProtectionTimelineScreen.kt` | F13 protection timeline screen (TML-L3) |
| `app/src/main/java/com/calyptra/app/ui/settings/ProtectionEventMapper.kt` | Pure events → day-groups mapper (TML-L3) |
| `app/src/main/java/com/calyptra/app/ui/settings/TimelineViewModel.kt` | Timeline state (DAO Flow → `TimelineDay` list) |
| `app/src/main/java/com/calyptra/app/ui/theme/Theme.kt` | Material3 theming + `LocalShieldColors` |
| `app/src/main/java/com/calyptra/app/ui/theme/Color.kt` | "Calyptra Greens" palette + `ShieldColors` (F12) |
| `app/src/main/java/com/calyptra/app/ui/theme/Type.kt` | Typography (Outfit + Plus Jakarta Sans) |

---

## Navigation

**File**: `MainActivity.kt`

```kotlin
NavHost(navController, startDestination = "home") {
    composable("home") { KidHomeScreen(...) }          // kid world
    composable("settings") { ParentSettingsScreen(...) } // requirePin(PARENT_SETTINGS)
    composable("whitelist") { WhitelistScreen(...) }   // linked from settings
    composable("timeline") { ProtectionTimelineScreen(...) } // linked from settings, read-only (F13)
}
// PinPromptDialog hosted once here, above the NavHost.
```

Four screens (F12 + F13): the kid home shows only the hero toggle + stats; the
gear icon routes through `requirePin(GatedAction.PARENT_SETTINGS)` — the grace
session opened on success makes the gated toggles inside proceed without
re-prompting. The timeline is read-only and reachable only via the gated
settings door, so it has no `GatedAction` of its own (FR-13.4). No deep
linking. Design docs: `docs/features/F12-ui-redesign.md`,
`docs/features/F13-protection-timeline.md`.

---

## STAT-L3: Real-Time Stats Display

**ViewModel** (`MainViewModel.kt`) — single combined state:

```kotlin
data class MainUiState(
    val isProtectionEnabled: Boolean = false,
    val gameAdsAllowed: Boolean = false,
    val adsBlockedToday: Int = 0,
    val adsBlockedTotal: Int = 0,
    val lastUpdate: Long = 0,
    val safeSearchEnabled: Boolean = true,
    val youtubeRestrictLevel: String = "strict",
    val conflictState: ConflictState = ConflictState.NONE,   // CFT-L3
    val batteryExempt: Boolean = true,                       // PWR-L3
    val alwaysOnActive: Boolean = false,                     // PWR-L3
    val batteryPromptShown: Boolean = true,                  // PWR-L3
    val blockedCategories: Set<String> = emptySet()          // SOC-L4
)

class MainViewModel(
    private val statsRepository: StatsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val pinManager: PinManager,
    private val networkMonitor: NetworkEnvironmentMonitor,
    private val powerStatusProvider: PowerStatusProvider,
    private val protectionEventRepository: ProtectionEventRepository  // TML-L2 hook
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        // 15 flows now: the original 7, plus VpnController.state,
        // protectionEnabled, networkMonitor.privateDnsActive,
        // vpnPermissionHeldByOther, batteryExempt, VpnController.alwaysOnActive,
        // batteryPromptShown, blockedCategories
    ) { values -> MainUiState(...) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )
}
```

`conflictState` is computed inside the `combine` via `ConflictStateReducer.reduce(prefEnabled, vpnState, vpnPermissionHeldByOther, privateDnsActive)` (CFT-L3). `onResumed(prepareNeeded)` is called from the UI on every ON_RESUME and refreshes `vpnPermissionHeldByOther`, `batteryExempt`, and `networkMonitor.refresh()`.

**Write methods on MainViewModel:**
- `setProtectionEnabled(Boolean)` — writes pref; on `false` also calls `VpnController.acknowledgeRevoked()` (CFT-L1); logs `ENABLED_USER`/`DISABLED_PARENT` via `protectionEventRepository.logAsync` (TML-L2); VPN started/stopped separately by UI
- `setGameAdsAllowed(Boolean)`
- `setSafeSearchEnabled(Boolean)`
- `setYoutubeRestrictLevel(String)` — `"off"`, `"moderate"`, `"strict"`
- `setCategoryBlocked(SocialCategory, Boolean)` — writes category key to `blockedCategories` pref
- `markBatteryPromptShown()`, `batteryExemptionIntent()`, `vpnSettingsIntent()` (PWR-L3)

**PIN gate flow** (`requirePin`, PIN-L3 — full details in [SECURITY.md](SECURITY.md)):
```kotlin
fun requirePin(action: GatedAction, onAuthorized: () -> Unit)
```
Routes protection-reducing actions through `PinGateReducer`: proceeds immediately during a grace session, otherwise sets `pinPrompt: StateFlow<PinPromptState?>` to show the setup or challenge dialog; `onAuthorized` runs after success. Enabling protection never calls this.

**Screen layout sketch (F12):**
```
KidHomeScreen                 ParentSettingsScreen (PIN-gated)
┌──────────────────┐          ┌──────────────────────┐
│            [⚙]   │          │ ← Parent Settings    │
│  (conflict)      │          │ ┌ Content filters ──┐ │
│   ╭────────╮     │          │ │ game ads/SafeSearch│ │
│   │ SHIELD │     │          │ │ YouTube level     │ │
│   ╰────────╯     │          │ ├ App Blocking ─────┤ │
│  Protected!      │          │ ├ Protection health ┤ │
│ ┌─────┐ ┌─────┐  │          │ ├ Whitelist → ──────┤ │
│ │ 42  │ │1 337│  │          │ ├ Protection history→┤ │
│ └─────┘ └─────┘  │          │ │ Blocklist updated │ │
└──────────────────┘          │ └───────────────────┘ │
                              └──────────────────────┘
```

---

## TML-L3: Protection Timeline Screen

**Files**: `ui/settings/ProtectionTimelineScreen.kt`, `ProtectionEventMapper.kt`, `TimelineViewModel.kt`

- Route `"timeline"`, navigated from the "Protection history" row in Parent
  Settings; read-only — no `GatedAction` (FR-13.4).
- `TimelineViewModel` (AndroidViewModel): `repository.eventsSince(0L)` →
  `ProtectionEventMapper.toDayGroups(events, zone, now)` → `StateFlow<List<TimelineDay>>`.
- **Mapper** (pure, JVM-tested): groups by local calendar day (newest first);
  events that `startsOffPeriod` get `offDurationMillis` to the next
  `isEnabledKind` event, or `stillOff = true` when the gap is open;
  `RESTORE_FAILED` continues a gap (no duration of its own); unknown type
  strings are dropped at the boundary.
- **Rows** (F12 visual language): leading icon in a circular container tinted
  via `LocalShieldColors` — protected/protectedGlow for `ENABLED_*`,
  unprotected/unprotectedGlow for off-markers, warningContainer for
  `RESTORE_FAILED`. Off-periods render "Off for 2 h 31 min" / "Still off"
  (FR-13.5). Day headers: Today / Yesterday / localized date.
- Stateless `ProtectionTimelineContent(days, onBack)` + `@Preview`s (populated
  + empty). Empty state ("No events yet") is also the post-data-wipe signal.

---

## WL-L2: Installed Apps Listing

**ViewModel** (`WhitelistViewModel.kt`):
```kotlin
class WhitelistViewModel(application: Application) : AndroidViewModel(application) {
    val installedApps: StateFlow<List<AppInfo>>    // All user-facing apps
    val whitelistedPackages: StateFlow<Set<String>> // Currently whitelisted

    fun toggleWhitelist(packageName: String, appName: String) { ... }
}
```

**App filtering**: Only shows apps that have a launcher intent (hides system services). Requires `QUERY_ALL_PACKAGES` permission.

---

## SYS-L3: Localization

**Supported locales**: English (default), Greek (`values-el/`)

| Key | English | Greek |
|-----|---------|-------|
| `app_name` | Calyptra | Calyptra |
| `protection_enabled` | Protection Active | Προστασία Ενεργή |
| `protection_disabled` | Protection Off | Προστασία Ανενεργή |
| `enable_protection` | Enable Protection | Ενεργοποίηση Προστασίας |
| `disable_protection` | Disable Protection | Απενεργοποίηση Προστασίας |
| `ads_blocked_today` | Ads blocked today | Διαφημίσεις σήμερα |
| `ads_blocked_total` | Total ads blocked | Σύνολο διαφημίσεων |

---

## Theme (F12 "Calyptra Greens")

**File**: `ui/theme/Color.kt`
- Full Material3 light + dark palettes (Fern/Mint greens, Lagoon blue
  secondary, Honey amber tertiary, Coral error). See
  `docs/features/F12-ui-redesign.md` §3 for the token table.
- `ShieldColors` data class: semantic protected/unprotected/warning colors,
  per theme (`LightShieldColors` / `DarkShieldColors`).

**File**: `ui/theme/Theme.kt`
- Custom light/dark schemes only — **no dynamic color** (the brand palette
  carries the protected/unprotected semantics, Constitution II).
- Provides `LocalShieldColors` CompositionLocal; components read state colors
  from it, never hardcoded hex.
- Dark mode follows system setting.

**File**: `ui/theme/Type.kt`
- Two families: Outfit (display/headline), Plus Jakarta Sans (title/body/label).
- `headlineMedium` 28sp bold = home status text; `headlineLarge` 32sp = stat numbers.

---

## Components

### ProtectionToggle (hero, F12)

**File**: `ui/components/ProtectionToggle.kt`

- 200dp circular shield button; the tap target IS the status display.
- `ic_shield_check` / `ic_shield_alert` vector drawables.
- Animated: 400ms color cross-fade on state change, breathing halo while
  protected, spring press-scale.
- Status (`home_status_on/off`) + subtitle text below; single tap toggles.

### KidStatsRow

**File**: `ui/components/StatsCard.kt`

- Two rounded `primaryContainer` pills: today / all time.
- `animateIntAsState` count-up, `%,d`-formatted numbers.

### ConflictBanner

**File**: `ui/components/ConflictBanner.kt` (extracted from old MainScreen)

- `ShieldColors.warningContainer` (amber) surface with leading warning icon.
- `OTHER_VPN` → "Re-enable" button; `PRIVATE_DNS` → "Open Settings" button.

### PinPromptDialog

**File**: `ui/pin/PinPromptDialog.kt`

- One dialog for both flows: setup (PIN-L2, 4-digit PIN entered twice, mismatch re-prompts) and challenge (PIN-L3, shows `attemptsLeft`).
- Lockout countdown via `LaunchedEffect` ticking `lockedUntilMillis` down per second (PIN-L4).
- Driven by `MainViewModel.pinPrompt` (`PinPromptState?`); callbacks: `submitPinSetup` / `submitPinChallenge` / `dismissPinPrompt`.

### CategorySection

**File**: `ui/settings/CategorySection.kt`

- "App Blocking" settings section (SOC-L4): one `Switch` per `SocialCategory`, checked iff its key is in `blockedCategories`.
- Brand display names intentionally not localized.
- Switches only report intent; the caller routes toggles through `requirePin(GatedAction.CATEGORIES)` (FR-11.5).
