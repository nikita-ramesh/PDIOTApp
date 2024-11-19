package com.specknet.pdiotapp.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_log")
data class ActivityLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val activityName: String,
    val startTime: Long,
    val endTime: Long
)