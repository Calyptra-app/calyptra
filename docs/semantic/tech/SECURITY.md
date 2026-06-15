# Security (Parental PIN) - Logic-to-Code Mapping

> **Progressive Disclosure Level 2** - PIN hashing, lockout, gating.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/calyptra/app/security/PinHasher.kt` | PBKDF2 hashing, constant-time verify |
| `app/src/main/java/com/calyptra/app/security/LockoutPolicy.kt` | Exponential lockout (pure function) |
| `app/src/main/java/com/calyptra/app/security/PinManager.kt` | `PinStore` interface, verification, grace session |
| `app/src/main/java/com/calyptra/app/ui/PinGate.kt` | `GatedAction` enum + `PinGateReducer` |
| `app/src/main/java/com/calyptra/app/ui/pin/PinPromptDialog.kt` | Setup/challenge dialog (see [UI-LAYER.md](UI-LAYER.md)) |

---

## PIN-L1: PIN Hashing & Verification

**Files**: `PinHasher.kt`, `PinManager.kt`

```kotlin
object PinHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray                 // SecureRandom, 16 bytes
    fun hash(pin: String, salt: ByteArray): ByteArray
    fun verify(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(...)           // constant-time compare
}
```

- Pure JVM, no Android deps (unit-testable without Robolectric).
- `PinManager.verify()` short-circuits while locked out — the hash is **never evaluated** during lockout, so brute-forcing burns no CPU and timing reveals nothing.

**Persistence boundary** (`PinManager.kt`):
```kotlin
interface PinStore {   // implemented by PreferencesRepository, faked in unit tests
    suspend fun getPinHash(): String        // Base64; "" means no PIN set
    suspend fun getPinSalt(): String
    suspend fun getFailedAttempts(): Int
    suspend fun getLockoutUntil(): Long
    suspend fun setPin(hash: String, salt: String)
    suspend fun setFailureState(attempts: Int, lockoutUntil: Long)
}
```

`PinManager(store, clock, hasher)` takes an injectable clock and hasher for tests. `verify()` returns a sealed `VerifyResult`: `Ok` / `Wrong(attemptsLeft)` / `LockedOut(untilMillis)`.

---

## PIN-L2: PIN Setup

**Files**: `PinManager.kt` - `setPin()`, `ui/pin/PinPromptDialog.kt`

- First gated action with no PIN set → `PinGateDecision.RequireSetup` → create-PIN flow (4 digits, entered twice, mismatch re-prompts).
- `setPin()` generates a fresh salt, stores Base64 hash + salt via `PinStore`, and resets failure state to `(0, 0L)`.

---

## PIN-L3: PIN Gate (Protection-Reducing Actions)

**File**: `ui/PinGate.kt`

```kotlin
enum class GatedAction {
    DISABLE_PROTECTION, GAME_ADS, SAFE_SEARCH, YOUTUBE_LEVEL, WHITELIST, CATEGORIES,
}

object PinGateReducer {
    fun decide(action: GatedAction, pinSet: Boolean, inGraceSession: Boolean): PinGateDecision =
        when {
            !pinSet -> PinGateDecision.RequireSetup
            inGraceSession -> PinGateDecision.Proceed
            else -> PinGateDecision.RequireChallenge
        }
}
```

**Flow**: UI calls `MainViewModel.requirePin(action) { onAuthorized() }` → reducer decides → either runs immediately (grace session) or shows `PinPromptState` (setup/challenge dialog); the pending action runs after success.

**Constitution interpretation**: Enabling protection is **deliberately NOT a `GatedAction`** — turning protection ON must stay a single kid-friendly tap (Constitution II). Only protection-*reducing* actions are gated.

**Grace session** (`PinManager`):
- Successful verify opens a **5-minute in-memory** grace window (`graceUntil`, `@Volatile`); subsequent gated actions proceed without re-prompt.
- Deliberately does not survive process death, and `MainActivity.onStop()` calls `pinManager.endGraceSession()` — backgrounding the app ends parental context.

---

## PIN-L4: Lockout Policy

**File**: `LockoutPolicy.kt` (pure function), persisted via `PinStore`

```kotlin
object LockoutPolicy {
    const val MAX_ATTEMPTS = 5
    private const val BASE_MS = 30_000L
    private const val CAP_MS = 300_000L

    fun lockoutUntil(failedAttempts: Int, lastFailureAt: Long): Long {
        if (failedAttempts < MAX_ATTEMPTS) return 0L
        val exponent = (failedAttempts - MAX_ATTEMPTS).coerceAtMost(4)
        val duration = (BASE_MS shl exponent).coerceAtMost(CAP_MS)
        return lastFailureAt + duration
    }
}
```

**Behavior**: 30 s lockout on the 5th consecutive failure, doubling per further failure (30 → 60 → 120 → 240 s), capped at 300 s. Failure count and `lockoutUntil` are **persisted** (survive process death/reboot). Successful verify resets to `(0, 0L)`. The dialog shows a live countdown while locked out.

---

## DI & Wiring

- `CalyptraApp.pinManager` — lazy singleton: `PinManager(preferencesRepository)` (`PreferencesRepository` implements `PinStore`).
- `MainViewModel` receives `pinManager` and exposes `requirePin` / `submitPinSetup` / `submitPinChallenge` / `dismissPinPrompt` (see [UI-LAYER.md](UI-LAYER.md)).
