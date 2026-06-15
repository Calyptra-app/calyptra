# Data Layer - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - Database, DAOs, repositories, preferences.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/calyptra/app/data/AppDatabase.kt` | Room database definition (v2) + `MIGRATION_1_2` |
| `app/src/main/java/com/calyptra/app/data/BlockedStat.kt` | Entity: daily block count |
| `app/src/main/java/com/calyptra/app/data/WhitelistedApp.kt` | Entity: per-app exclusion |
| `app/src/main/java/com/calyptra/app/data/ProtectionEvent.kt` | Entity + `ProtectionEventType` enum (TML-L1) |
| `app/src/main/java/com/calyptra/app/data/StatsDao.kt` | Stats database queries |
| `app/src/main/java/com/calyptra/app/data/WhitelistDao.kt` | Whitelist database queries |
| `app/src/main/java/com/calyptra/app/data/ProtectionEventDao.kt` | Event log queries + transactional insert-and-prune (TML-L1) |
| `app/src/main/java/com/calyptra/app/data/StatsRepository.kt` | Stats business logic wrapper |
| `app/src/main/java/com/calyptra/app/data/ProtectionEventRepository.kt` | Event log writer/reader, watchdog dedup (TML-L2) |
| `app/src/main/java/com/calyptra/app/data/RetentionPolicy.kt` | Pure F13 retention rule (FR-13.6) |
| `app/src/main/java/com/calyptra/app/data/PreferencesRepository.kt` | DataStore preferences |
| `app/src/main/java/com/calyptra/app/data/Converters.kt` | Room type converters |

---

## Database Configuration

**File**: `AppDatabase.kt`

```kotlin
@Database(
    entities = [BlockedStat::class, WhitelistedApp::class, ProtectionEvent::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase()
```

- **Database name**: `calyptra_database`
- **Version**: 2 — `MIGRATION_1_2` (additive: creates `protection_events` + timestamp index, FR-13.8)
- **Entities**: 3 (BlockedStat, WhitelistedApp, ProtectionEvent)
- **Type converters**: Instant <-> Long, LocalDate <-> String
- **Schema export**: `app/schemas/` (KSP `room.schemaLocation`), consumed as androidTest assets by `Migration1To2Test`

---

## STAT-L1: Per-Day Block Count Tracking

**Entity** (`BlockedStat.kt`):
```kotlin
@Entity
data class BlockedStat(
    @PrimaryKey val date: LocalDate,  // YYYY-MM-DD
    val count: Int                     // Blocks on this day
)
```

**DAO** (`StatsDao.kt`):
```kotlin
@Dao
interface StatsDao {
    @Query("SELECT SUM(count) FROM BlockedStat")
    fun getTotalBlocked(): Flow<Int?>

    @Query("SELECT count FROM BlockedStat WHERE date = :date")
    fun getBlockedCountForDate(date: LocalDate): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: BlockedStat)

    @Query("INSERT INTO BlockedStat (date, count) VALUES (:date, 1) ON CONFLICT(date) DO UPDATE SET count = count + 1")
    suspend fun incrementCount(date: LocalDate)
}
```

**Increment pattern** (called from `StatsRepository.incrementCount()` which is called from VPN service):
```kotlin
statsDao.incrementCount(LocalDate.now())
```

Single atomic SQL `INSERT ... ON CONFLICT DO UPDATE` — creates row with count=1 on first call of the day, increments on subsequent calls. No separate `insertIfNotExists` step needed.

---

## STAT-L2: Today / Total Aggregation

**File**: `StatsRepository.kt`

```kotlin
class StatsRepository(private val statsDao: StatsDao) {
    val totalBlocked: Flow<Int> = statsDao.getTotalBlocked().map { it ?: 0 }
    val todayBlocked: Flow<Int> = statsDao.getBlockedCountForDate(LocalDate.now()).map { it ?: 0 }

    suspend fun incrementCount() {
        statsDao.incrementCount(LocalDate.now())
    }
}
```

Both are reactive `Flow`s — the UI updates automatically when the VPN service increments counts.

---

## WL-L3: Whitelist Persistence

**Entity** (`WhitelistedApp.kt`):
```kotlin
@Entity
data class WhitelistedApp(
    @PrimaryKey val packageName: String,  // e.g. "com.sybo.subwaysurfers"
    val appName: String,                   // "Subway Surfers"
    val addedAt: Instant                   // When whitelisted
)
```

**DAO** (`WhitelistDao.kt`):
```kotlin
@Dao
interface WhitelistDao {
    @Query("SELECT * FROM WhitelistedApp")
    fun getAll(): Flow<List<WhitelistedApp>>

    @Query("SELECT packageName FROM WhitelistedApp")
    suspend fun getAllPackageNames(): List<String>  // Used by VPN service at setup

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: WhitelistedApp)

    @Delete
    suspend fun delete(app: WhitelistedApp)  // Takes entity, not packageName string
}
```

**Usage**:
- `getAll()` (Flow) → `WhitelistScreen` displays checkbox state.
- `getAllPackageNames()` (blocking suspend) → VPN service reads `List<String>` at tunnel setup. Only package names needed; avoids fetching full entities.
- `insert()` / `delete()` → Toggle whitelist from UI.

---

## TML-L1: Protection Event Storage

**Entity** (`ProtectionEvent.kt`) — protection STATE only, never domains or browsing (Constitution I):
```kotlin
@Entity(tableName = "protection_events", indices = [Index("timestampMillis")])
data class ProtectionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val type: String   // ProtectionEventType.name
)

enum class ProtectionEventType {
    ENABLED_USER, ENABLED_BOOT, ENABLED_WATCHDOG,        // isEnabledKind
    DISABLED_PARENT, REVOKED_OTHER_VPN, STOPPED_UNEXPECTED, // startsOffPeriod
    RESTORE_FAILED                                        // continues a gap, never starts one
}
```

**DAO** (`ProtectionEventDao.kt`):
```kotlin
@Insert suspend fun insert(event: ProtectionEvent)
fun eventsSince(cutoffMillis: Long): Flow<List<ProtectionEvent>>   // newest first (ts DESC, id DESC)
suspend fun latestType(): String?                                  // watchdog dedup
suspend fun prune(keepCount: Int, minTimestampMillis: Long)
@Transaction suspend fun insertAndPrune(event, keepCount, minTimestampMillis)  // FR-13.6, one transaction
```

**Retention** (`RetentionPolicy.kt`, pure + JVM-tested): a row is pruned only
when it is BOTH outside the newest-500 AND older than 30 days. The 30-day
window is never punctured (off-durations stay honest); the cap holds whenever
fewer than 500 transitions occur inside the window. `RetentionPolicy.retain`
is the pure mirror of the prune SQL; `ProtectionEventDaoTest` pins them to
each other.

---

## TML-L2: Protection Event Capture

**File**: `ProtectionEventRepository.kt` — lazy singleton in `CalyptraApp`,
constructed with the DAO, an app-lifetime `Dispatchers.IO` scope, and an
injected clock (`() -> Long`).

- `log(type)` (suspend) — stamps clock, `insertAndPrune` in one transaction.
- `logAsync(type)` — fire-and-forget on the IO scope; no caller blocks (FR-13.2).
- `logUnexpectedStopOnce()` / `logRestoreFailedOnce()` — watchdog dedup: a
  15-min re-check of the same outage must not duplicate rows (FR-13.1).

**Hook sites** (existing seams only — `VpnController.state` deliberately NOT
hooked: transitions alone can't carry the "why" and would double-log):

| Site | Event |
|------|-------|
| `MainViewModel.setProtectionEnabled(true/false)` | `ENABLED_USER` / `DISABLED_PARENT` (false path is PIN-gated) |
| `AdBlockVpnService.onRevoke()` | `REVOKED_OTHER_VPN` |
| `BootReceiver` (pref-checked auto-start) | `ENABLED_BOOT` |
| `VpnWatchdogWorker` RESTART_VPN | `STOPPED_UNEXPECTED` (once) → `ENABLED_WATCHDOG` |
| `VpnWatchdogWorker` ALERT_PERMISSION_LOST | `STOPPED_UNEXPECTED` (once) → `RESTORE_FAILED` (once) |

Never written on the DNS packet path. Budget: 500 rows × ~40 B ≈ 20 KB.

---

## SYS-L2: Preferences Persistence

**File**: `PreferencesRepository.kt`

**Construction**: Takes `Context`. Uses extension property `Context.dataStore` (DataStore named `"settings"`):
```kotlin
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesRepository(private val context: Context) { ... }
```

**Keys and flows:**

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `protection_enabled` | Boolean | `false` | VPN master switch state |
| `last_blocklist_update` | Long | `0L` | Epoch millis of last successful update |
| `blocklist_version` | String | `"bundled"` | `"bundled"` or `"remote-update"` |
| `game_ads_allowed` | Boolean | `false` | Whether game ad SDK domains are exempted |
| `safe_search_enabled` | Boolean | `true` | Whether DNS SafeSearch redirect is active |
| `youtube_restrict_level` | String | `"strict"` | `"off"`, `"moderate"`, or `"strict"` |

**Write methods:**
```kotlin
suspend fun setProtectionEnabled(enabled: Boolean)
suspend fun setSafeSearchEnabled(enabled: Boolean)
suspend fun setYoutubeRestrictLevel(level: String)
suspend fun setGameAdsAllowed(allowed: Boolean)
suspend fun updateBlocklistMetadata(timestamp: Long, version: String)  // atomic: sets both at once
```

`updateBlocklistMetadata` writes `last_blocklist_update` and `blocklist_version` in a single `dataStore.edit { }` call.

---

## Type Converters

**File**: `Converters.kt`

```kotlin
class Converters {
    @TypeConverter fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter fun fromLocalDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }
}
```

---

## Test Coverage

| Test | File | Covers |
|------|------|--------|
| Insert + read stats | `androidTest/.../data/StatsDaoTest.kt` | STAT-L1 |
| Increment count | `androidTest/.../data/StatsDaoTest.kt` | STAT-L1 |
| Total aggregation | `androidTest/.../data/StatsDaoTest.kt` | STAT-L2 |
| Retention rule (pure) | `test/.../data/RetentionPolicyTest.kt` | TML-L1 |
| Repository log/dedup/concurrency | `test/.../data/ProtectionEventRepositoryTest.kt` | TML-L2 |
| Event DAO round-trip + prune SQL | `androidTest/.../data/ProtectionEventDaoTest.kt` | TML-L1 |
| v1 → v2 migration preserves data | `androidTest/.../data/Migration1To2Test.kt` | FR-13.8 |

## Schema Reference

See [schemas/database.json](../schemas/database.json) for the full JSON schema.
