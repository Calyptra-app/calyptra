package com.calyptra.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.calyptra.app.security.PinStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesRepository(private val context: Context) : PinStore {

    private val PROTECTION_ENABLED = booleanPreferencesKey("protection_enabled")
    private val LAST_BLOCKLIST_UPDATE = longPreferencesKey("last_blocklist_update")
    private val BLOCKLIST_VERSION = stringPreferencesKey("blocklist_version")
    private val GAME_ADS_ALLOWED = booleanPreferencesKey("game_ads_allowed")
    private val SAFE_SEARCH_ENABLED = booleanPreferencesKey("safe_search_enabled")
    private val YOUTUBE_RESTRICT_LEVEL = stringPreferencesKey("youtube_restrict_level")
    private val BATTERY_PROMPT_SHOWN = booleanPreferencesKey("battery_prompt_shown")
    private val BLOCKED_CATEGORIES = stringSetPreferencesKey("blocked_categories")
    private val YIELDED_TO_OTHER_VPN = booleanPreferencesKey("yielded_to_other_vpn")
    private val PIN_HASH = stringPreferencesKey("pin_hash")
    private val PIN_SALT = stringPreferencesKey("pin_salt")
    private val PIN_FAILED_ATTEMPTS = intPreferencesKey("pin_failed_attempts")
    private val PIN_LOCKOUT_UNTIL = longPreferencesKey("pin_lockout_until")

    val protectionEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PROTECTION_ENABLED] ?: false
        }
        
    val lastBlocklistUpdate: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_BLOCKLIST_UPDATE] ?: 0L
        }
        
    val blocklistVersion: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[BLOCKLIST_VERSION] ?: "bundled"
        }

    val gameAdsAllowed: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[GAME_ADS_ALLOWED] ?: false
        }

    val safeSearchEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SAFE_SEARCH_ENABLED] ?: true
        }

    val youtubeRestrictLevel: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[YOUTUBE_RESTRICT_LEVEL] ?: "strict"
        }

    suspend fun setSafeSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SAFE_SEARCH_ENABLED] = enabled
        }
    }

    suspend fun setYoutubeRestrictLevel(level: String) {
        context.dataStore.edit { preferences ->
            preferences[YOUTUBE_RESTRICT_LEVEL] = level
        }
    }

    suspend fun setGameAdsAllowed(allowed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GAME_ADS_ALLOWED] = allowed
        }
    }

    val blockedCategories: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[BLOCKED_CATEGORIES] ?: emptySet()
        }

    suspend fun setCategoryBlocked(key: String, blocked: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[BLOCKED_CATEGORIES] ?: emptySet()
            preferences[BLOCKED_CATEGORIES] = if (blocked) current + key else current - key
        }
    }

    val batteryPromptShown: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BATTERY_PROMPT_SHOWN] ?: false
        }

    suspend fun setBatteryPromptShown() {
        context.dataStore.edit { preferences ->
            preferences[BATTERY_PROMPT_SHOWN] = true
        }
    }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PROTECTION_ENABLED] = enabled
        }
    }

    /** True after another VPN revoked us (CFT-L1). Persisted so the watchdog and
     *  boot receiver keep yielding across process death — otherwise we'd restart
     *  and kill the other VPN. Cleared when the parent explicitly toggles
     *  protection. */
    val yieldedToOtherVpn: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[YIELDED_TO_OTHER_VPN] ?: false
        }

    suspend fun setYieldedToOtherVpn(yielded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[YIELDED_TO_OTHER_VPN] = yielded
        }
    }
    
    suspend fun updateBlocklistMetadata(timestamp: Long, version: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_BLOCKLIST_UPDATE] = timestamp
            preferences[BLOCKLIST_VERSION] = version
        }
    }

    // PinStore (PIN-L1, PIN-L4) — hash/salt are Base64, "" means no PIN set.

    override suspend fun getPinHash(): String =
        context.dataStore.data.first()[PIN_HASH] ?: ""

    override suspend fun getPinSalt(): String =
        context.dataStore.data.first()[PIN_SALT] ?: ""

    override suspend fun getFailedAttempts(): Int =
        context.dataStore.data.first()[PIN_FAILED_ATTEMPTS] ?: 0

    override suspend fun getLockoutUntil(): Long =
        context.dataStore.data.first()[PIN_LOCKOUT_UNTIL] ?: 0L

    override suspend fun setPin(hash: String, salt: String) {
        context.dataStore.edit { preferences ->
            preferences[PIN_HASH] = hash
            preferences[PIN_SALT] = salt
        }
    }

    override suspend fun setFailureState(attempts: Int, lockoutUntil: Long) {
        context.dataStore.edit { preferences ->
            preferences[PIN_FAILED_ATTEMPTS] = attempts
            preferences[PIN_LOCKOUT_UNTIL] = lockoutUntil
        }
    }
}
