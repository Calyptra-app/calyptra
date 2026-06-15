package com.calyptra.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calyptra.app.CalyptraApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** Feeds the protection timeline (TML-L3): DAO Flow → day groups. Read-only. */
class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as CalyptraApp).protectionEventRepository

    val days: StateFlow<List<TimelineDay>> = repository.eventsSince(0L)
        .map { events ->
            ProtectionEventMapper.toDayGroups(events, ZoneId.systemDefault(), System.currentTimeMillis())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
