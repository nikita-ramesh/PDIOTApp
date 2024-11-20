package com.specknet.pdiotapp.summary

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.DatePicker
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var dateTextView: TextView

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var selectedDate: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        // Initialize views
        db = AppDatabase.getDatabase(this)
        activityLogDao = db.activityLogDao()
        listView = findViewById(R.id.activity_summary_list_view)
        changeDateButton = findViewById(R.id.change_date_button)
        dateTextView = findViewById(R.id.date_text_view)

        // Set the initial date to today's date
        updateDateTextView()

        changeDateButton.setOnClickListener {
            showDatePicker()
        }

        Log.e("ActivitySummary", "onCreate called")

        // Check if the database is empty before inserting placeholder data
        Thread {
            val entryCount = activityLogDao.getEntryCount()
            Log.e("PlaceholderData", "Database entry count: $entryCount")
            if (entryCount == 0) {
                insertPlaceholderData()
            } else {
                // Display summary for today's date initially
                runOnUiThread {
                    displayActivitySummary()
                }
            }
        }.start()
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            this,
            { _: DatePicker, year: Int, month: Int, day: Int ->
                selectedDate.set(year, month, day)
                updateDateTextView() // Update the date in the TextView
                displayActivitySummary() // Display summary for the selected date
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun updateDateTextView() {
        // Format the selected date and update the TextView
        val formattedDate = dateFormat.format(selectedDate.time)
        dateTextView.text = "Date: $formattedDate"
    }

    private fun displayActivitySummary() {
        val startCalendar = Calendar.getInstance()
        startCalendar.time = selectedDate.time
        startCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startCalendar.set(Calendar.MINUTE, 0)
        startCalendar.set(Calendar.SECOND, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)
        val startTime = startCalendar.timeInMillis

        val endCalendar = Calendar.getInstance()
        endCalendar.time = startCalendar.time
        endCalendar.add(Calendar.DAY_OF_MONTH, 1)
        val endTime = endCalendar.timeInMillis

        Log.d("ActivitySummary", "Fetching data from $startTime to $endTime")

        // Fetch and aggregate data from the database
        Thread {
            val activitySummaries = activityLogDao.getActivitySummaryForDate(startTime, endTime)

            Log.d("ActivitySummary", "Retrieved ${activitySummaries.size} summaries")

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
                Log.d("ActivitySummary", "Updating ListView with data")
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

    // ... Rest of the code remains unchanged ...

private fun insertPlaceholderData() {
        Log.e("PlaceholderData", "insertPlaceholderData() called")
        val startCalendar = Calendar.getInstance()
        startCalendar.time = selectedDate.time
        startCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startCalendar.set(Calendar.MINUTE, 0)
        startCalendar.set(Calendar.SECOND, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)
        val sampleDate = startCalendar.time
        // Get yesterday's date based on sampleDate
        val yesterdayCalendar = Calendar.getInstance()
        yesterdayCalendar.time = sampleDate
        yesterdayCalendar.add(Calendar.DAY_OF_MONTH, -1)
        val yesterdayDate = yesterdayCalendar.time

        Log.d("PlaceholderData", "Sample date: $sampleDate (Millis: ${sampleDate.time})")

//        0 -> "SittingStanding"
//        1 -> "lyingBack"
//        2 -> "lyingLeft"
//        3 -> "lyingRight"
//        4 -> "lyingStomach"
//        5 -> "miscMovement"
//        6 -> "normalWalking"
//        7 -> "running"
//        8 -> "shuffleWalking"
//        9 -> "ascending"
//        10 -> "descending"
//        else -> "miscMovement"

        // Sample data list
        val sampleData = listOf(
            ActivityLogEntry(
                activityName = "SittingStanding",
                startTime = getTimeInMillisForDate(sampleDate, 9, 0),
                endTime = getTimeInMillisForDate(sampleDate, 9, 30)
            ),
            ActivityLogEntry(
                activityName = "lyingBack",
                startTime = getTimeInMillisForDate(sampleDate, 10, 0),
                endTime = getTimeInMillisForDate(sampleDate, 10, 15)
            ),
            ActivityLogEntry(
                activityName = "running",
                startTime = getTimeInMillisForDate(sampleDate, 11, 0),
                endTime = getTimeInMillisForDate(sampleDate, 12, 0)
            ),
            // Yesterday's samples
            ActivityLogEntry(
                activityName = "SittingStanding",
                startTime = getTimeInMillisForDate(yesterdayDate, 8, 0),
                endTime = getTimeInMillisForDate(yesterdayDate, 8, 30)
            ),
            ActivityLogEntry(
                activityName = "running",
                startTime = getTimeInMillisForDate(yesterdayDate, 9, 0),
                endTime = getTimeInMillisForDate(yesterdayDate, 9, 45)
            ),
            ActivityLogEntry(
                activityName = "running",
                startTime = getTimeInMillisForDate(yesterdayDate, 10, 15),
                endTime = getTimeInMillisForDate(yesterdayDate, 11, 0)
            )
        )

        // Insert the sample data into the database on a background thread
        Thread {
            sampleData.forEach { entry ->
                activityLogDao.insert(entry)
                Log.d(
                    "PlaceholderData",
                    "Inserted entry: ${entry.activityName}, startTime: ${entry.startTime}, endTime: ${entry.endTime}"
                )
            }
            // After inserting data, refresh the summary
            runOnUiThread {
                Log.d("PlaceholderData", "Data insertion complete, updating UI")
                displayActivitySummary()
            }
        }.start()
    }

    private fun getTimeInMillisForDate(date: Date, hourOfDay: Int, minute: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

}