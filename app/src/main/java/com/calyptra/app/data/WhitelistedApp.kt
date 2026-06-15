package com.calyptra.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity
data class WhitelistedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val addedAt: Instant
)
