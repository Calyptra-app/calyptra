package com.calyptra.app.ui.settings

import com.calyptra.app.data.ProtectionEvent
import com.calyptra.app.data.ProtectionEventType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One timeline row (TML-L3). [offDurationMillis] is set only on events that
 *  open an off-period: the gap until the next enabled event, or until now
 *  when protection hasn't come back ([stillOff]). */
data class TimelineEntry(
    val id: Long,
    val timestampMillis: Long,
    val type: ProtectionEventType,
    val offDurationMillis: Long? = null,
    val stillOff: Boolean = false
)

/** Entries of one local calendar day, newest first. */
data class TimelineDay(val date: LocalDate, val entries: List<TimelineEntry>)

/** Pure events → day-groups mapper (FR-13.5); JVM-tested. */
object ProtectionEventMapper {

    fun toDayGroups(events: List<ProtectionEvent>, zone: ZoneId, nowMillis: Long): List<TimelineDay> {
        // Oldest-first working order; unknown type names from a future schema
        // are dropped at this boundary rather than crashing the timeline.
        val known = events.mapNotNull { event ->
            val type = ProtectionEventType.entries.firstOrNull { it.name == event.type }
            type?.let { Triple(event.id, event.timestampMillis, it) }
        }.sortedWith(compareBy({ it.second }, { it.first }))

        val entries = known.mapIndexed { index, (id, timestamp, type) ->
            if (!type.startsOffPeriod) {
                TimelineEntry(id, timestamp, type)
            } else {
                val nextEnable = known.subList(index + 1, known.size)
                    .firstOrNull { it.third.isEnabledKind }
                if (nextEnable != null) {
                    TimelineEntry(id, timestamp, type, offDurationMillis = nextEnable.second - timestamp)
                } else {
                    TimelineEntry(id, timestamp, type, offDurationMillis = nowMillis - timestamp, stillOff = true)
                }
            }
        }

        return entries
            .sortedWith(compareByDescending<TimelineEntry> { it.timestampMillis }.thenByDescending { it.id })
            .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .map { (date, dayEntries) -> TimelineDay(date, dayEntries) }
            .sortedByDescending { it.date }
    }
}
