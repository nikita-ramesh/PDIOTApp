package com.specknet.pdiotapp.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.specknet.pdiotapp.database.entities.ActivityLogEntry

@Dao
interface ActivityLogDao {

    @Insert
    fun insert(entry: ActivityLogEntry)

    @Update
    fun update(entry: ActivityLogEntry)

    @Query("SELECT * FROM activity_log WHERE date(startTime / 1000, 'unixepoch') = date('now')")
    fun getTodayEntries(): List<ActivityLogEntry>
}