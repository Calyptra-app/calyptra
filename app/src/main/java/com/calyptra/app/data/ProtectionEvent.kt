package com.calyptra.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One protection state transition (TML-L1, FR-13.1). Records protection STATE
 * only — never domains, queries, or browsing activity (Constitution I).
 */
@Entity(tableName = "protection_events", indices = [Index("timestampMillis")])
data class ProtectionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val type: String // ProtectionEventType.name; String keeps the schema enum-agnostic
)

enum class ProtectionEventType {
    ENABLED_USER,        // single tap on the shield (kid or parent)
    ENABLED_BOOT,        // BootReceiver auto-start (SYS-L1)
    ENABLED_WATCHDOG,    // watchdog restored protection (PWR-L2)
    DISABLED_PARENT,     // PIN-authorized disable (PIN-L3)
    REVOKED_OTHER_VPN,   // onRevoke: another VPN took the slot (CFT-L1)
    STOPPED_UNEXPECTED,  // watchdog found protection down (PWR-L2)
    RESTORE_FAILED;      // watchdog could not restore; alert posted (PWR-L4)

    /** Protection running after this event. */
    val isEnabledKind: Boolean
        get() = this == ENABLED_USER || this == ENABLED_BOOT || this == ENABLED_WATCHDOG

    /** Opens an off-period whose duration runs until the next enabled event.
     *  RESTORE_FAILED continues an existing gap, it never starts one. */
    val startsOffPeriod: Boolean
        get() = this == DISABLED_PARENT || this == REVOKED_OTHER_VPN || this == STOPPED_UNEXPECTED
}
