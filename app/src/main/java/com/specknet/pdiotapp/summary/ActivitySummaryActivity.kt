package com.specknet.pdiotapp.summary

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.DatePicker
import android.widget.ListView
import android.widget.SimpleAdapter
import androidx.appcompat.app.AppCompatActivity
import com.specknet.pdiotapp.R
import com.specknet.pdiotapp.database.AppDatabase
import com.specknet.pdiotapp.database.dao.ActivityLogDao
import com.specknet.pdiotapp.database.entities.ActivityLogEntry
import com.specknet.pdiotapp.database.entities.ActivitySummary
import java.text.SimpleDateFormat
import java.util.*

class ActivitySummaryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var activityLogDao: ActivityLogDao
    private lateinit var listView: ListView
    private lateinit var changeDateButton: Button

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var selectedDate: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        // Initialize database and DAO
        db = AppDatabase.getDatabase(this)
        activityLogDao = db.activityLogDao()

        listView = findViewById(R.id.activity_summary_list_view)
        changeDateButton = findViewById(R.id.change_date_button)

        changeDateButton.setOnClickListener {
            showDatePicker()
        }

        // Display summary for today's date initially
        displayActivitySummary()
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            this,
            { _: DatePicker, year: Int, month: Int, day: Int ->
                selectedDate.set(year, month, day)
                displayActivitySummary()
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun displayActivitySummary() {
        val dateString = dateFormat.format(selectedDate.time)

        // Fetch and aggregate data from the database
        Thread {
            val activitySummaries = activityLogDao.getActivitySummaryForDate(dateString)

            // Prepare data for the list view
            val data = activitySummaries.map { summary ->
                val totalSeconds = summary.totalDuration / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60

                mapOf(
                    "activityName" to summary.activityName,
                    "totalDuration" to String.format("%02d:%02d:%02d", hours, minutes, seconds)
                )
            }

            runOnUiThread {
                val adapter = SimpleAdapter(
                    this,
                    data,
                    android.R.layout.simple_list_item_2,
                    arrayOf("activityName", "totalDuration"),
                    intArrayOf(android.R.id.text1, android.R.id.text2)
                )
                listView.adapter = adapter
            }
        }.start()
    }
}