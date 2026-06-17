package com.calyptra.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calyptra.app.R
import com.calyptra.app.data.ProtectionEventType
import com.calyptra.app.ui.theme.CalyptraTheme
import com.calyptra.app.ui.theme.LocalShieldColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * F13 protection timeline (TML-L3): the parent's tamper log. Read-only, day
 * grouped, reachable only through the PIN-gated settings door (FR-13.4) — no
 * GatedAction of its own, viewing history reduces nothing.
 */
@Composable
fun ProtectionTimelineScreen(
    onBack: () -> Unit,
    viewModel: TimelineViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    ProtectionTimelineContent(days = days, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionTimelineContent(
    days: List<TimelineDay>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timeline_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (days.isEmpty()) {
            // Also what a parent sees after an app-data wipe — which, with the
            // PIN re-prompt, is itself the tamper signal (F13 tamper model).
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.timeline_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                days.forEach { day ->
                    item(key = "day-${day.date}") {
                        TimelineDayHeader(date = day.date)
                    }
                    items(day.entries, key = { it.id }) { entry ->
                        TimelineEventRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineDayHeader(date: LocalDate) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.timeline_today)
        today.minusDays(1) -> stringResource(R.string.timeline_yesterday)
        else -> remember(date) {
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun TimelineEventRow(entry: TimelineEntry) {
    val shield = LocalShieldColors.current
    // State semantics from LocalShieldColors (FR-13.5): green for ENABLED_*,
    // red for off-markers, amber for the watchdog giving up.
    val (icon, tint, container) = when {
        entry.type.isEnabledKind -> Triple(
            painterResource(R.drawable.ic_shield_check), shield.protected, shield.protectedGlow
        )
        entry.type == ProtectionEventType.RESTORE_FAILED -> Triple(
            rememberVectorPainter(Icons.Default.Warning), shield.onWarningContainer, shield.warningContainer
        )
        else -> Triple(
            painterResource(R.drawable.ic_shield_alert), shield.unprotected, shield.unprotectedGlow
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(container, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(painter = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(eventLabel(entry.type), style = MaterialTheme.typography.titleMedium)
            offPeriodLabel(entry)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.stillOff) shield.unprotected else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = formatTime(entry.timestampMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun eventLabel(type: ProtectionEventType): String = stringResource(
    when (type) {
        ProtectionEventType.ENABLED_USER -> R.string.timeline_event_enabled_user
        ProtectionEventType.ENABLED_BOOT -> R.string.timeline_event_enabled_boot
        ProtectionEventType.ENABLED_WATCHDOG -> R.string.timeline_event_enabled_watchdog
        ProtectionEventType.DISABLED_PARENT -> R.string.timeline_event_disabled_parent
        ProtectionEventType.REVOKED_OTHER_VPN -> R.string.timeline_event_revoked
        ProtectionEventType.STOPPED_UNEXPECTED -> R.string.timeline_event_stopped
        ProtectionEventType.RESTORE_FAILED -> R.string.timeline_event_restore_failed
    }
)

/** "Off for 2 h 31 min" connector under closed off-periods; "Still off" while
 *  the gap is open (FR-13.5). On-events and RESTORE_FAILED carry no label. */
@Composable
private fun offPeriodLabel(entry: TimelineEntry): String? = when {
    entry.stillOff -> stringResource(R.string.timeline_still_off)
    entry.offDurationMillis != null ->
        stringResource(R.string.timeline_off_for, formatDuration(entry.offDurationMillis))
    else -> null
}

@Composable
private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> stringResource(R.string.timeline_duration_days, days, hours)
        hours > 0 -> stringResource(R.string.timeline_duration_hours, hours, minutes)
        minutes > 0 -> stringResource(R.string.timeline_duration_minutes, minutes)
        else -> stringResource(R.string.timeline_duration_under_minute)
    }
}

@Composable
private fun formatTime(timestampMillis: Long): String {
    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}

@Preview(showBackground = true)
@Composable
private fun ProtectionTimelinePreview() {
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    CalyptraTheme {
        ProtectionTimelineContent(
            days = listOf(
                TimelineDay(
                    date = LocalDate.now(),
                    entries = listOf(
                        TimelineEntry(5, now - hour, ProtectionEventType.STOPPED_UNEXPECTED,
                            offDurationMillis = hour, stillOff = true),
                        TimelineEntry(4, now - 3 * hour, ProtectionEventType.ENABLED_WATCHDOG),
                        TimelineEntry(3, now - 4 * hour, ProtectionEventType.REVOKED_OTHER_VPN,
                            offDurationMillis = hour)
                    )
                ),
                TimelineDay(
                    date = LocalDate.now().minusDays(1),
                    entries = listOf(
                        TimelineEntry(2, now - 25 * hour, ProtectionEventType.ENABLED_USER),
                        TimelineEntry(1, now - 27 * hour, ProtectionEventType.DISABLED_PARENT,
                            offDurationMillis = 2 * hour + 31 * 60_000L)
                    )
                )
            ),
            onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProtectionTimelineEmptyPreview() {
    CalyptraTheme {
        ProtectionTimelineContent(days = emptyList(), onBack = {})
    }
}
