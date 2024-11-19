package com.specknet.pdiotapp.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.specknet.pdiotapp.database.entities.ActivityLogEntry
import com.specknet.pdiotapp.database.entities.ActivitySummary

@Dao
interface ActivityLogDao {

    @Insert
    fun insert(entry: ActivityLogEntry)

    @Update
    fun update(entry: ActivityLogEntry)

    @Query("SELECT * FROM activity_log WHERE date(startTime / 1000, 'unixepoch') = date('now')")
    fun getTodayEntries(): List<ActivityLogEntry>

    @Query("""
    SELECT activityName, SUM(endTime - startTime) as totalDuration
    FROM activity_log
    WHERE date(startTime / 1000, 'unixepoch', 'localtime') = :date
    GROUP BY activityName
""")
    fun getActivitySummaryForDate(date: String): List<ActivitySummary>

}