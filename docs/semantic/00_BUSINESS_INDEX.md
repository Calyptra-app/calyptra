# Calyptra - Business Feature Index

> **Progressive Disclosure Level 0** - Start here. Each Logic ID links to implementation details.

## Identity

| Field | Value |
|-------|-------|
| App | Calyptra |
| Package | `com.calyptra.app` |
| Platform | Android (min SDK 26 / Android 8.0) |
| Language | Kotlin 1.9.22 |
| UI | Jetpack Compose + Material 3 |
| Architecture | Single Activity, MVVM + Repository |
| Distribution | Sideloaded APK (<10MB) |
| Privacy Model | 100% on-device, zero telemetry |

## Feature Map

### F1: Ad Blocking (Core)

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| VPN-L1 | VPN tunnel establishment | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l1) |
| VPN-L2 | DNS query interception | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l2) |
| VPN-L3 | Blocked domain response (0.0.0.0) | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l3) |
| VPN-L4 | Upstream DNS forwarding (allowed) | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l4) |
| VPN-L5 | VPN state management | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l5) |
| VPN-L6 | Foreground service notification | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l6) |

### F2: Domain Blocklist

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| BLK-L1 | Bundled blocklist loading (~4,541 domains) | Done | [BLOCKLIST.md](tech/BLOCKLIST.md#blk-l1) |
| BLK-L2 | Domain matching (+ subdomain) | Done | [BLOCKLIST.md](tech/BLOCKLIST.md#blk-l2) |
| BLK-L3 | Remote blocklist fetching (Hagezi Light ~118k) | Done | [BLOCKLIST.md](tech/BLOCKLIST.md#blk-l3) |
| BLK-L4 | Bundled + remote merge (saved to filesDir) | Done | [BLOCKLIST.md](tech/BLOCKLIST.md#blk-l4) |
| BLK-L5 | Game ads toggle (@Volatile, no VPN restart needed) | Done | [BLOCKLIST.md](tech/BLOCKLIST.md#blk-l5) |
| BLK-BG | Weekly auto-update (WorkManager, 3 retries) | Done | [BACKGROUND.md](tech/BACKGROUND.md#blk-l5) |

### F3: Statistics

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| STAT-L1 | Per-day block count tracking | Done | [DATA-LAYER.md](tech/DATA-LAYER.md#stat-l1) |
| STAT-L2 | Today / total aggregation | Done | [DATA-LAYER.md](tech/DATA-LAYER.md#stat-l2) |
| STAT-L3 | Real-time stats display | Done | [UI-LAYER.md](tech/UI-LAYER.md#stat-l3) |

### F4: App Whitelist

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| WL-L1 | Per-app VPN exclusion (getAllPackageNames at setup) | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#wl-l1) |
| WL-L2 | Installed apps listing (launcher intent filter) | Done | [UI-LAYER.md](tech/UI-LAYER.md#wl-l2) |
| WL-L3 | Whitelist persistence (Flow + suspend getAllPackageNames) | Done | [DATA-LAYER.md](tech/DATA-LAYER.md#wl-l3) |

### F5: System Integration

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| SYS-L1 | Boot receiver (checks VPN permission + pref via goAsync; logs ENABLED_BOOT) | Done | [BACKGROUND.md](tech/BACKGROUND.md#sys-l1) |
| SYS-L2 | Preferences persistence (6 keys, atomic updateBlocklistMetadata) | Done | [DATA-LAYER.md](tech/DATA-LAYER.md#sys-l2) |
| SYS-L3 | Greek + English localization | Done | [UI-LAYER.md](tech/UI-LAYER.md#sys-l3) |

### F6: SafeSearch & Parental Controls

| Logic ID | Feature | Status | Tech Doc |
|----------|---------|--------|----------|
| VPN-L3.5 | DoH canary NXDOMAIN (8 providers blocked) | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l35) |
| VPN-L3.6 | SafeSearch DNS redirect (Google ~139 TLDs, Bing, DuckDuckGo) + YouTube Restricted Mode (off/moderate/strict) | Done | [VPN-SERVICE.md](tech/VPN-SERVICE.md#vpn-l36) |

### F8: DNS / VPN Conflict Handling (Sprint 01)

| Logic ID | Feature | Status | Spec |
|----------|---------|--------|------|
| CFT-L1 | onRevoke detection, sealed VpnState (Stopped/Running/Revoked) | Done | [F8](../features/F8-dns-conflict-handling.md) |
| CFT-L2 | Parent alert on revocation (`calyptra_alerts` channel, id 2) | Done | [F8](../features/F8-dns-conflict-handling.md) |
| CFT-L3 | Conflict banner (OTHER_VPN / PRIVATE_DNS) via ConflictStateReducer | Done | [F8](../features/F8-dns-conflict-handling.md) |
| CFT-L4 | Private DNS strict-mode detection (NetworkEnvironmentMonitor) | Done | [F8](../features/F8-dns-conflict-handling.md) |

### F9: Power Management & Continuity (Sprint 01)

| Logic ID | Feature | Status | Spec |
|----------|---------|--------|------|
| PWR-L1 | Restart resilience + RestartGuard crash-loop breaker | Done | [F9](../features/F9-power-management.md) |
| PWR-L2 | VpnWatchdogWorker (15 min, restores or alerts, id 3) | Done | [F9](../features/F9-power-management.md) |
| PWR-L3 | Battery exemption flow + Always-on VPN detection | Done | [F9](../features/F9-power-management.md) |
| PWR-L4 | Connectivity-change handling (expedited watchdog check) | Done | [F9](../features/F9-power-management.md) |

### F10: Parental PIN Gate (Sprint 01)

| Logic ID | Feature | Status | Spec |
|----------|---------|--------|------|
| PIN-L1 | PBKDF2 PIN storage (PinHasher, PinManager, PinStore) | Done | [F10](../features/F10-parental-pin-gate.md) |
| PIN-L2 | PIN setup flow (lazy, on first gated action) | Done | [F10](../features/F10-parental-pin-gate.md) |
| PIN-L3 | Challenge gate for protection reductions (6 GatedActions) | Done | [F10](../features/F10-parental-pin-gate.md) |
| PIN-L4 | Persistent exponential lockout (LockoutPolicy) | Done | [F10](../features/F10-parental-pin-gate.md) |

### F11: Social Media Category Blocking (Sprint 01)

| Logic ID | Feature | Status | Spec |
|----------|---------|--------|------|
| SOC-L1 | 8 bundled category files (res/raw/category_*.txt, ~59 domains) | Done | [F11](../features/F11-social-media-blocking.md) |
| SOC-L2 | CategoryBlockManager + NXDOMAIN verdict (DnsPolicy ordering) | Done | [F11](../features/F11-social-media-blocking.md) |
| SOC-L3 | `blocked_categories` pref, live toggle without VPN restart | Done | [F11](../features/F11-social-media-blocking.md) |
| SOC-L4 | "App Blocking" settings section, PIN-gated | Done | [F11](../features/F11-social-media-blocking.md) |

### F13: Protection Timeline (Sprint 03)

| Logic ID | Feature | Status | Spec |
|----------|---------|--------|------|
| TML-L1 | protection_events entity + DAO (insertAndPrune, newest-500 ∪ 30-day retention), Room v1→v2 migration | Done | [F13](../features/F13-protection-timeline.md) |
| TML-L2 | ProtectionEventRepository + hooks (ViewModel, onRevoke, BootReceiver, watchdog with dedup) | Done | [F13](../features/F13-protection-timeline.md) |
| TML-L3 | Timeline screen ("timeline" route, day groups, off-durations, ShieldColors) | Done | [F13](../features/F13-protection-timeline.md) |

### F7: Pending / Known Gaps

| Logic ID | Description | Status | Ref |
|----------|-------------|--------|-----|
| GAP-01 | Memory profiling (<50MB gate) | Pending | Constitution |
| GAP-02 | APK size analysis (<10MB gate) | Pending | Constitution |
| GAP-03 | VPN restart after crash | Resolved (Sprint 01, PWR-L1/L2) | CHK003 |
| GAP-04 | VPN conflict with other VPN apps | Resolved (Sprint 01, CFT-L1..L4) | CHK004 |
| GAP-05 | DNS over TCP handling | Open | CHK007 |
| GAP-06 | Malformed DNS packet resilience | Open | CHK009 |
| GAP-07 | Network connectivity change handling | Resolved (Sprint 01, PWR-L4) | CHK016 |
| GAP-08 | Blocklist authenticity verification | Open | CHK022 |

## Constitution Performance Gates

| Gate | Requirement | Verified |
|------|-------------|----------|
| APK Size | < 10 MB | Pending |
| Memory | < 50 MB active | Pending |
| DNS Latency | < 10 ms overhead | Pending |
| VPN Setup | < 2 seconds | Pending |

## Quick Navigation

- **Architecture overview**: [ARCHITECTURE.md](ARCHITECTURE.md)
- **VPN & DNS engine**: [tech/VPN-SERVICE.md](tech/VPN-SERVICE.md)
- **Blocklist system**: [tech/BLOCKLIST.md](tech/BLOCKLIST.md)
- **Database & data**: [tech/DATA-LAYER.md](tech/DATA-LAYER.md)
- **UI & screens**: [tech/UI-LAYER.md](tech/UI-LAYER.md)
- **Background work**: [tech/BACKGROUND.md](tech/BACKGROUND.md)
- **Database schema**: [schemas/database.json](schemas/database.json)
- **Preferences schema**: [schemas/preferences.json](schemas/preferences.json)
