package com.calyptra.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity
data class BlockedStat(
    @PrimaryKey val date: LocalDate,
    val count: Int
)
