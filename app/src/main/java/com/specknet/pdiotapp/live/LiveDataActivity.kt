package com.specknet.pdiotapp.live

// Import necessary Android and Kotlin libraries
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.specknet.pdiotapp.R
import com.specknet.pdiotapp.utils.Constants
import com.specknet.pdiotapp.utils.RESpeckLiveData
import com.specknet.pdiotapp.utils.ThingyLiveData
import kotlin.collections.ArrayList

import android.widget.TextView // for the classification label


/**
 * This is the main activity class for displaying live data from devices.
 * It extends AppCompatActivity, which is a base class for activities
 * that use the support library action bar features.
 */
class LiveDataActivity : AppCompatActivity() {

    // LineDataSets for Respeck accelerometer data (x, y, z)
    lateinit var dataSet_res_accel_x: LineDataSet
    lateinit var dataSet_res_accel_y: LineDataSet
    lateinit var dataSet_res_accel_z: LineDataSet

    // LineDataSets for Thingy accelerometer data (x, y, z)
    lateinit var dataSet_thingy_accel_x: LineDataSet
    lateinit var dataSet_thingy_accel_y: LineDataSet
    lateinit var dataSet_thingy_accel_z: LineDataSet

    // Variable to keep track of time for plotting on the x-axis
    var time = 0f

    // LineData objects to hold all data sets for the charts
    lateinit var allRespeckData: LineData
    lateinit var allThingyData: LineData

    // LineChart views for displaying the graphs in the UI
    lateinit var respeckChart: LineChart
    lateinit var thingyChart: LineChart

    // BroadcastReceivers to receive live data updates from devices
    lateinit var respeckLiveUpdateReceiver: BroadcastReceiver
    lateinit var thingyLiveUpdateReceiver: BroadcastReceiver

    // Loopers for handling background threads for the receivers
    lateinit var looperRespeck: Looper
    lateinit var looperThingy: Looper

    // IntentFilters to specify which broadcasts to listen for
    val filterTestRespeck = IntentFilter(Constants.ACTION_RESPECK_LIVE_BROADCAST)
    val filterTestThingy = IntentFilter(Constants.ACTION_THINGY_BROADCAST)

    /**
     * onCreate is called when the activity is first created.
     * It initializes the activity, sets up the UI, and registers broadcast receivers.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the layout for this activity
        setContentView(R.layout.activity_live_data)
        val classificationView: TextView = findViewById(R.id.classification_label)


        // Initialize and set up the charts
        setupCharts()

        // Set up the broadcast receiver for Respeck device data
        respeckLiveUpdateReceiver = object : BroadcastReceiver() { // listen for updates from the Respeck
            override fun onReceive(context: Context, intent: Intent) {

                // Log the thread name for debugging purposes
                Log.i("thread", "I am running on thread = " + Thread.currentThread().name)

                val action = intent.action

                // Check if the received broadcast is from Respeck device
                if (action == Constants.ACTION_RESPECK_LIVE_BROADCAST) {

                    // Retrieve live data from the intent
                    val liveData =
                        intent.getSerializableExtra(Constants.RESPECK_LIVE_DATA) as RESpeckLiveData
                    Log.d("Live", "onReceive: liveData = " + liveData)

                    // Extract accelerometer data (x, y, z)
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    // Increment time for the x-axis of the graph
                    time += 1

                    // Update the graph with new data
                    updateGraph("respeck", x, y, z)

                    // simple proof of concept of changing the classification label based on incoming data
                    runOnUiThread {
                        if (x > 0) {
                            classificationView.text = "walking" // this is how to change the classification label in code
                        } else {
                            classificationView.text = "eating"
                        }
                    }
                }
            }
        }

        // Register the Respeck broadcast receiver on a background thread
        val handlerThreadRespeck = HandlerThread("bgThreadRespeckLive")
        handlerThreadRespeck.start()
        looperRespeck = handlerThreadRespeck.looper
        val handlerRespeck = Handler(looperRespeck)
        this.registerReceiver(respeckLiveUpdateReceiver, filterTestRespeck, null, handlerRespeck)

        // Set up the broadcast receiver for Thingy device data
        thingyLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                // Log the thread name for debugging purposes
                Log.i("thread", "I am running on thread = " + Thread.currentThread().name)

                val action = intent.action

                // Check if the received broadcast is from Thingy device
                if (action == Constants.ACTION_THINGY_BROADCAST) {

                    // Retrieve live data from the intent
                    val liveData =
                        intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                    Log.d("Live", "onReceive: liveData = " + liveData)

                    // Extract accelerometer data (x, y, z)
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    // Increment time for the x-axis of the graph
                    time += 1

                    // Update the graph with new data
                    updateGraph("thingy", x, y, z)
                }
            }
        }

        // Register the Thingy broadcast receiver on a background thread
        val handlerThreadThingy = HandlerThread("bgThreadThingyLive")
        handlerThreadThingy.start()
        looperThingy = handlerThreadThingy.looper
        val handlerThingy = Handler(looperThingy)
        this.registerReceiver(thingyLiveUpdateReceiver, filterTestThingy, null, handlerThingy)
    }

    /**
     * Sets up the charts by initializing data sets and configuring their appearance.
     */
    fun setupCharts() {
        // Find the LineChart views in the layout
        respeckChart = findViewById(R.id.respeck_chart)
        thingyChart = findViewById(R.id.thingy_chart)

        // Reset time for plotting
        time = 0f

        // Initialize data entries lists for Respeck accelerometer data
        val entries_res_accel_x = ArrayList<Entry>()
        val entries_res_accel_y = ArrayList<Entry>()
        val entries_res_accel_z = ArrayList<Entry>()

        // Create LineDataSets for Respeck accelerometer data
        dataSet_res_accel_x = LineDataSet(entries_res_accel_x, "Accel X")
        dataSet_res_accel_y = LineDataSet(entries_res_accel_y, "Accel Y")
        dataSet_res_accel_z = LineDataSet(entries_res_accel_z, "Accel Z")

        // Configure appearance of Respeck data sets
        dataSet_res_accel_x.setDrawCircles(false)
        dataSet_res_accel_y.setDrawCircles(false)
        dataSet_res_accel_z.setDrawCircles(false)

        // Set colors for Respeck data sets
        dataSet_res_accel_x.color = ContextCompat.getColor(this, R.color.red)
        dataSet_res_accel_y.color = ContextCompat.getColor(this, R.color.green)
        dataSet_res_accel_z.color = ContextCompat.getColor(this, R.color.blue)

        // Add Respeck data sets to a list
        val dataSetsRes = ArrayList<ILineDataSet>()
        dataSetsRes.add(dataSet_res_accel_x)
        dataSetsRes.add(dataSet_res_accel_y)
        dataSetsRes.add(dataSet_res_accel_z)

        // Create LineData object with Respeck data sets and set it to the chart
        allRespeckData = LineData(dataSetsRes)
        respeckChart.data = allRespeckData
        respeckChart.invalidate() // Refresh the chart

        // Repeat the same steps for Thingy accelerometer data
        time = 0f
        val entries_thingy_accel_x = ArrayList<Entry>()
        val entries_thingy_accel_y = ArrayList<Entry>()
        val entries_thingy_accel_z = ArrayList<Entry>()

        dataSet_thingy_accel_x = LineDataSet(entries_thingy_accel_x, "Accel X")
        dataSet_thingy_accel_y = LineDataSet(entries_thingy_accel_y, "Accel Y")
        dataSet_thingy_accel_z = LineDataSet(entries_thingy_accel_z, "Accel Z")

        dataSet_thingy_accel_x.setDrawCircles(false)
        dataSet_thingy_accel_y.setDrawCircles(false)
        dataSet_thingy_accel_z.setDrawCircles(false)

        dataSet_thingy_accel_x.color = ContextCompat.getColor(this, R.color.red)
        dataSet_thingy_accel_y.color = ContextCompat.getColor(this, R.color.green)
        dataSet_thingy_accel_z.color = ContextCompat.getColor(this, R.color.blue)

        val dataSetsThingy = ArrayList<ILineDataSet>()
        dataSetsThingy.add(dataSet_thingy_accel_x)
        dataSetsThingy.add(dataSet_thingy_accel_y)
        dataSetsThingy.add(dataSet_thingy_accel_z)

        allThingyData = LineData(dataSetsThingy)
        thingyChart.data = allThingyData
        thingyChart.invalidate() // Refresh the chart
    }

    /**
     * Updates the specified graph with new accelerometer data.
     *
     * @param graph Specifies which graph to update ("respeck" or "thingy").
     * @param x Accelerometer x-axis value.
     * @param y Accelerometer y-axis value.
     * @param z Accelerometer z-axis value.
     */
    fun updateGraph(graph: String, x: Float, y: Float, z: Float) {
        // Check which graph to update based on the 'graph' parameter
        if (graph == "respeck") {
            // Add new entries to Respeck data sets
            dataSet_res_accel_x.addEntry(Entry(time, x))
            dataSet_res_accel_y.addEntry(Entry(time, y))
            dataSet_res_accel_z.addEntry(Entry(time, z))

            // Update the UI on the main thread
            runOnUiThread {
                allRespeckData.notifyDataChanged()
                respeckChart.notifyDataSetChanged()
                respeckChart.invalidate()
                // Set the visible range of the x-axis
                respeckChart.setVisibleXRangeMaximum(150f)
                // Move the view to the latest data point
                respeckChart.moveViewToX(respeckChart.lowestVisibleX + 40)
            }
        } else if (graph == "thingy") {
            // Add new entries to Thingy data sets
            dataSet_thingy_accel_x.addEntry(Entry(time, x))
            dataSet_thingy_accel_y.addEntry(Entry(time, y))
            dataSet_thingy_accel_z.addEntry(Entry(time, z))

            // Update the UI on the main thread
            runOnUiThread {
                allThingyData.notifyDataChanged()
                thingyChart.notifyDataSetChanged()
                thingyChart.invalidate()
                // Set the visible range of the x-axis
                thingyChart.setVisibleXRangeMaximum(150f)
                // Move the view to the latest data point
                thingyChart.moveViewToX(thingyChart.lowestVisibleX + 40)
            }
        }
    }

    /**
     * onDestroy is called when the activity is destroyed.
     * It unregisters the broadcast receivers and stops the background threads.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Unregister the broadcast receivers
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)
        // Quit the loopers to stop background threads
        looperRespeck.quit()
        looperThingy.quit()
    }
}
