# Background Work - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - WorkManager, boot receiver.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/calyptra/app/worker/BlocklistUpdateWorker.kt` | Periodic blocklist refresh |
| `app/src/main/java/com/calyptra/app/worker/VpnWatchdogWorker.kt` | VPN continuity check + scheduler (PWR-L2) |
| `app/src/main/java/com/calyptra/app/vpn/RestartGuard.kt` | Crash-loop breaker for VPN startup (PWR-L1) |
| `app/src/main/java/com/calyptra/app/receiver/BootReceiver.kt` | Auto-start VPN on device boot |

---

## BLK-L5: Weekly Blocklist Auto-Update

**File**: `BlocklistUpdateWorker.kt`
**Worker type**: `CoroutineWorker` (WorkManager)
**Schedule**: Every 7 days, battery-optimized constraints.

**Registration** (called from `CalyptraApp.onCreate()` via `setupWorker()`):
```kotlin
val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(7, TimeUnit.DAYS)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    ).build()

WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "BlocklistUpdate",
    ExistingPeriodicWorkPolicy.KEEP,
    request
)
```

**Worker logic** (`doWork()`):
```kotlin
override suspend fun doWork(): Result {
    return try {
        val newDomains = BlocklistUpdater().fetch()
        if (newDomains.isNotEmpty()) {
            val app = applicationContext as CalyptraApp
            app.blocklistManager.saveUpdate(newDomains)   // saves file + re-initializes in-memory set
            app.preferencesRepository.updateBlocklistMetadata(
                System.currentTimeMillis(),
                "remote-update"        // stored version string (not "remote")
            )
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
```

**Retry logic**: Up to 3 attempts (`runAttemptCount < 3`), then `Result.failure()` (WorkManager stops retrying).

**Constraints:**
- Requires network connectivity.
- Requires battery not low.
- Uses `KEEP` policy (no restart if already scheduled).

---

## PWR-L2: VPN Watchdog

**File**: `VpnWatchdogWorker.kt` (contains `VpnWatchdogPolicy`, `VpnWatchdogWorker`, `VpnWatchdogScheduler`)

Periodic continuity check: restores the VPN after an OS kill, or alerts the parent when restore is impossible because the permission was lost.

**Pure decision table** (`VpnWatchdogPolicy.decide`):

```kotlin
fun decide(protectionEnabled: Boolean, vpnRunning: Boolean, permissionHeld: Boolean): WatchdogAction =
    when {
        !protectionEnabled -> WatchdogAction.NONE
        vpnRunning -> WatchdogAction.NONE
        permissionHeld -> WatchdogAction.RESTART_VPN          // → VpnController.startVpn()
        else -> WatchdogAction.ALERT_PERMISSION_LOST          // → notification id 3
    }
```

**Worker inputs** (`doWork()`): `preferencesRepository.protectionEnabled.first()`, `VpnController.isRunning.value`, `VpnService.prepare(context) == null`. Alert posted on channel `calyptra_alerts` (`WATCHDOG_NOTIFICATION_ID = 3`).

**Event logging (TML-L2, F13)**: `RESTART_VPN` logs `STOPPED_UNEXPECTED` (deduped via `logUnexpectedStopOnce`) before the restart, then `ENABLED_WATCHDOG` after; `ALERT_PERMISSION_LOST` logs `STOPPED_UNEXPECTED` + `RESTORE_FAILED`, each once per outage — repeated 15-min re-checks of the same outage add no rows.

**Scheduling** (`VpnWatchdogScheduler`):
- `schedule()` — 15-min `PeriodicWorkRequest` **with a 15-min initial delay**, unique name `"vpn_watchdog"`, `KEEP` policy. The initial delay exists because a fresh periodic request runs immediately, racing the still-establishing VPN (`isRunning` not yet true) — before F13 that just caused a redundant `startVpn`, but with event logging it wrote a spurious `STOPPED_UNEXPECTED`/`ENABLED_WATCHDOG` pair on every enable (found on-device, Sprint 03).
- `cancel()` — cancels the unique work.
- `checkNow()` — expedited one-shot check (unique name `"vpn_watchdog_now"`, `REPLACE`), fired on network changes (PWR-L4) and by the crash-loop retry path (PWR-L1).

**Lockstep with the pref** (`CalyptraApp.setupWatchdogLockstep()`): an app-scope coroutine collects `protectionEnabled` and calls `schedule()`/`cancel()` accordingly — the watchdog is scheduled **iff protection is intended**, no matter who writes the pref (ViewModel, boot receiver, future writers). (FR-9.2)

---

## PWR-L1: Restart Guard

**File**: `vpn/RestartGuard.kt`

Crash-loop breaker for VPN startup: allows retries after a failure unless **3 failures landed within a 5-minute sliding window** — then stop retrying and alert the parent instead of looping silently.

```kotlin
class RestartGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 3,
    private val windowMs: Long = 5 * 60_000L
) {
    @Synchronized fun shouldRetryAfterFailure(): Boolean  // records failure, prunes window, size < max
    @Synchronized fun reset()                             // on successful establish()
}
```

**Usage** (`AdBlockVpnService`):
- Held as a **process-wide companion instance** (`private val restartGuard = RestartGuard()`) so the 5-min window survives service restarts.
- `handleStartFailure()`: if `shouldRetryAfterFailure()` → `VpnWatchdogScheduler.checkNow()`; otherwise post the watchdog alert (id 3) and give up. Then `stopSelf()`.
- `restartGuard.reset()` called right after a successful `builder.establish()`.
- A null-intent `onStartCommand` (START_STICKY restart after an OS kill) is the restore path; `stopVpn()` cancels the packet loop *before* closing the interface so the read doesn't log a phantom failure.

---

## SYS-L1: Boot Receiver (Auto-Start)

**File**: `BootReceiver.kt`

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (VpnService.prepare(context) != null) return   // no VPN permission

        val app = context.applicationContext as CalyptraApp
        val pendingResult = goAsync()                     // async pref read
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.preferencesRepository.protectionEnabled.first()) {
                    VpnController.startVpn(context)
                    app.protectionEventRepository.log(ProtectionEventType.ENABLED_BOOT)  // TML-L2
                }
            } finally { pendingResult.finish() }
        }
    }
}
```

**Since F13**: BootReceiver reads the `protection_enabled` preference itself
(via `goAsync` + IO coroutine) and starts the service only when protection is
intended — so `ENABLED_BOOT` is logged exactly on real auto-starts, never on a
boot where protection was off. The service's own pref check remains as a
defensive second layer (it still `stopSelf()`s if the pref is false).

**Permission required**: `RECEIVE_BOOT_COMPLETED`

---

## Lifecycle Notes

| Event | What Happens |
|-------|-------------|
| App install | WorkManager scheduled via `CalyptraApp.setupWorker()`, VPN not started |
| First enable | `MainViewModel.setProtectionEnabled(true)` → pref saved → UI calls `VpnController.startVpn(context)` |
| Disable | `VpnController.stopVpn(context)` sends `ACTION_STOP` intent |
| App killed (swipe) | VPN service may be killed; `START_STICKY` means Android may restart it |
| Device reboot | `BootReceiver` checks permission + pref → `VpnController.startVpn()` + logs `ENABLED_BOOT` (TML-L2) |
| Weekly interval | WorkManager fires `BlocklistUpdateWorker` |
| Every 15 min (protection on) | `VpnWatchdogWorker` runs decision table (PWR-L2) |
| Network change (VPN up) | `NetworkEnvironmentMonitor` callback → `VpnWatchdogScheduler.checkNow()` (PWR-L4) |
| VPN start fails repeatedly | `RestartGuard` trips after 3 failures / 5 min → alert id 3 (PWR-L1) |
| Network lost | Worker deferred until connectivity restored |
| Battery low | Worker deferred until battery recovers |
| Update succeeds | `blocklistManager.saveUpdate()` reloads in-memory set immediately |
