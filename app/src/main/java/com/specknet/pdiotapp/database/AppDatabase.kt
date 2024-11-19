package com.specknet.pdiotapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.specknet.pdiotapp.database.dao.ActivityLogDao
import com.specknet.pdiotapp.database.entities.ActivityLogEntry

@Database(entities = [ActivityLogEntry::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(context.applicationContext,
                AppDatabase::class.java, "app_database")
                .build()
    }
}