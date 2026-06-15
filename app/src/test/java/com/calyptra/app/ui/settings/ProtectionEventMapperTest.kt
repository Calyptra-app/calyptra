package com.calyptra.app.ui.settings

import com.calyptra.app.data.ProtectionEvent
import com.calyptra.app.data.ProtectionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** TML-L3 mapper: DAO rows → day groups with derived off-durations (FR-13.5). */
class ProtectionEventMapperTest {

    private val zone = ZoneId.of("Europe/Athens")
    private var nextId = 1L

    private fun at(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun event(type: ProtectionEventType, date: String, time: String) =
        ProtectionEvent(id = nextId++, timestampMillis = at(date, time), type = type.name)

    private val now = at("2026-06-11", "12:00:00")

    @Test
    fun `empty list maps to no day groups`() {
        assertEquals(emptyList<TimelineDay>(), ProtectionEventMapper.toDayGroups(emptyList(), zone, now))
    }

    @Test
    fun `events group by local day, newest day and newest entry first`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                event(ProtectionEventType.ENABLED_USER, "2026-06-10", "09:00:00"),
                event(ProtectionEventType.DISABLED_PARENT, "2026-06-11", "08:00:00"),
                event(ProtectionEventType.ENABLED_USER, "2026-06-11", "10:00:00")
            ),
            zone, now
        )
        assertEquals(listOf(LocalDate.parse("2026-06-11"), LocalDate.parse("2026-06-10")), days.map { it.date })
        assertEquals(
            listOf(ProtectionEventType.ENABLED_USER, ProtectionEventType.DISABLED_PARENT),
            days[0].entries.map { it.type }
        )
    }

    @Test
    fun `off-duration computed from parent disable to next enable`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                event(ProtectionEventType.DISABLED_PARENT, "2026-06-11", "08:00:00"),
                event(ProtectionEventType.ENABLED_USER, "2026-06-11", "10:31:00")
            ),
            zone, now
        )
        val off = days[0].entries.first { it.type == ProtectionEventType.DISABLED_PARENT }
        assertEquals((2 * 60 + 31) * 60_000L, off.offDurationMillis)
        assertFalse(off.stillOff)
        // The enable row itself carries no duration.
        assertNull(days[0].entries.first { it.type == ProtectionEventType.ENABLED_USER }.offDurationMillis)
    }

    @Test
    fun `off-duration works for revoke and unexpected stop with watchdog restore`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                event(ProtectionEventType.REVOKED_OTHER_VPN, "2026-06-11", "07:00:00"),
                event(ProtectionEventType.ENABLED_USER, "2026-06-11", "07:30:00"),
                event(ProtectionEventType.STOPPED_UNEXPECTED, "2026-06-11", "09:00:00"),
                event(ProtectionEventType.ENABLED_WATCHDOG, "2026-06-11", "09:15:00")
            ),
            zone, now
        )
        val entries = days[0].entries.associateBy { it.type }
        assertEquals(30 * 60_000L, entries.getValue(ProtectionEventType.REVOKED_OTHER_VPN).offDurationMillis)
        assertEquals(15 * 60_000L, entries.getValue(ProtectionEventType.STOPPED_UNEXPECTED).offDurationMillis)
    }

    @Test
    fun `restore failure continues an existing gap instead of starting a new one`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                event(ProtectionEventType.REVOKED_OTHER_VPN, "2026-06-11", "07:00:00"),
                event(ProtectionEventType.RESTORE_FAILED, "2026-06-11", "07:15:00"),
                event(ProtectionEventType.ENABLED_USER, "2026-06-11", "08:00:00")
            ),
            zone, now
        )
        val entries = days[0].entries.associateBy { it.type }
        assertEquals(60 * 60_000L, entries.getValue(ProtectionEventType.REVOKED_OTHER_VPN).offDurationMillis)
        assertNull(entries.getValue(ProtectionEventType.RESTORE_FAILED).offDurationMillis)
    }

    @Test
    fun `open-ended off-period is marked still off with duration up to now`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(event(ProtectionEventType.DISABLED_PARENT, "2026-06-11", "10:00:00")),
            zone, now
        )
        val off = days[0].entries.single()
        assertTrue(off.stillOff)
        assertEquals(2 * 60 * 60_000L, off.offDurationMillis) // 10:00 → 12:00 now
    }

    @Test
    fun `off-period across midnight splits into both day groups with full duration`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                event(ProtectionEventType.STOPPED_UNEXPECTED, "2026-06-10", "23:50:00"),
                event(ProtectionEventType.ENABLED_WATCHDOG, "2026-06-11", "00:20:00")
            ),
            zone, now
        )
        assertEquals(2, days.size)
        assertEquals(LocalDate.parse("2026-06-11"), days[0].date)
        assertEquals(ProtectionEventType.ENABLED_WATCHDOG, days[0].entries.single().type)
        val off = days[1].entries.single()
        assertEquals(ProtectionEventType.STOPPED_UNEXPECTED, off.type)
        assertEquals(30 * 60_000L, off.offDurationMillis)
    }

    @Test
    fun `unknown event type strings are dropped, not crashed on`() {
        val days = ProtectionEventMapper.toDayGroups(
            listOf(
                ProtectionEvent(id = 1, timestampMillis = at("2026-06-11", "09:00:00"), type = "FUTURE_TYPE"),
                event(ProtectionEventType.ENABLED_USER, "2026-06-11", "10:00:00")
            ),
            zone, now
        )
        assertEquals(1, days.single().entries.size)
    }

    @Test
    fun `input order does not matter`() {
        val a = event(ProtectionEventType.DISABLED_PARENT, "2026-06-11", "08:00:00")
        val b = event(ProtectionEventType.ENABLED_USER, "2026-06-11", "09:00:00")
        val asc = ProtectionEventMapper.toDayGroups(listOf(a, b), zone, now)
        val desc = ProtectionEventMapper.toDayGroups(listOf(b, a), zone, now)
        assertEquals(asc, desc)
    }
}
