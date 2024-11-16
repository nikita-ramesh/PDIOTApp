package com.specknet.pdiotapp.live

import android.annotation.SuppressLint
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
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.widget.TextView

class LiveDataActivity : AppCompatActivity() {
    private lateinit var interpreter: Interpreter

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

    // Define the sliding window size
    private val windowSize = 100  // Example: Keep the last 100 data points for each axis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)
        val classificationView: TextView = findViewById(R.id.classification_label)

        interpreter = Interpreter(loadModelFile())

        setupCharts()

        // Set up the broadcast receiver for Respeck device data
        respeckLiveUpdateReceiver = object : BroadcastReceiver() {
            @SuppressLint("SetTextI18n")
            override fun onReceive(context: Context, intent: Intent) {
                val liveData = intent.getSerializableExtra(Constants.RESPECK_LIVE_DATA) as RESpeckLiveData
                val xRespeck = liveData.accelX
                val yRespeck = liveData.accelY
                val zRespeck = liveData.accelZ

                val liveDataThingy = intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                val xThingy = liveDataThingy.accelX
                val yThingy = liveDataThingy.accelY
                val zThingy = liveDataThingy.accelZ

                // Preprocess the data
                val (processedRespeckData, processedThingyData) = preprocessData(
                    floatArrayOf(xRespeck, yRespeck, zRespeck),
                    floatArrayOf(xThingy, yThingy, zThingy)
                )

                val input = arrayOf(processedRespeckData, processedThingyData)
                val output = Array(1) { FloatArray(11) } // 11 classes (modify based on actual number of categories)

                // Run inference
                interpreter.run(input, output)

                val predictedClass = output[0].indexOfFirst { it == output[0].maxOrNull() }

                runOnUiThread {
                    classificationView.text = "Predicted: ${getActivityLabel(predictedClass)}"
                }

                Log.d("Prediction", "Predicted class: $predictedClass")
            }
        }

        val handlerThreadRespeck = HandlerThread("bgThreadRespeckLive")
        handlerThreadRespeck.start()
        looperRespeck = handlerThreadRespeck.looper
        val handlerRespeck = Handler(looperRespeck)

        // Register the receiver for Respeck (No flags needed for below Android U)
        registerReceiver(respeckLiveUpdateReceiver, filterTestRespeck, null, handlerRespeck)

        // Set up the broadcast receiver for Thingy device data
        thingyLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val liveData = intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                val x = liveData.accelX
                val y = liveData.accelY
                val z = liveData.accelZ

                time += 1
                updateGraph("thingy", x, y, z)
            }
        }

        val handlerThreadThingy = HandlerThread("bgThreadThingyLive")
        handlerThreadThingy.start()
        looperThingy = handlerThreadThingy.looper
        val handlerThingy = Handler(looperThingy)

        // Register the receiver for Thingy (No flags needed for below Android U)
        registerReceiver(thingyLiveUpdateReceiver, filterTestThingy)
    }

    private fun loadModelFile(): ByteBuffer {
        val fileDescriptor = assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val byteArray = inputStream.readBytes()
        val byteBuffer = ByteBuffer.allocateDirect(byteArray.size)
        byteBuffer.order(ByteOrder.nativeOrder())
        byteBuffer.put(byteArray)
        return byteBuffer
    }

    fun getActivityLabel(predictedClass: Int): String {
        return when (predictedClass) {
            0 -> "Sitting/Standing"
            1 -> "Lying Back"
            2 -> "Lying Left"
            3 -> "Lying Right"
            4 -> "Lying Stomach"
            5 -> "Miscellaneous Movement"
            6 -> "Normal Walking"
            7 -> "Running"
            8 -> "Shuffle Walking"
            9 -> "Ascending"
            10 -> "Descending"
            else -> "Unknown"
        }
    }

    fun preprocessData(respeckData: FloatArray, thingyData: FloatArray): Pair<FloatArray, FloatArray> {
        return Pair(respeckData, thingyData)
    }

    fun setupCharts() {
        respeckChart = findViewById(R.id.respeck_chart)
        thingyChart = findViewById(R.id.thingy_chart)

        time = 0f
        setupLineChart("respeck")
        setupLineChart("thingy")
    }

    fun setupLineChart(chartType: String) {
        val entriesX = ArrayList<Entry>()
        val entriesY = ArrayList<Entry>()
        val entriesZ = ArrayList<Entry>()

        val dataSetX = LineDataSet(entriesX, "Accel X")
        val dataSetY = LineDataSet(entriesY, "Accel Y")
        val dataSetZ = LineDataSet(entriesZ, "Accel Z")

        dataSetX.setDrawCircles(false)
        dataSetY.setDrawCircles(false)
        dataSetZ.setDrawCircles(false)

        dataSetX.color = ContextCompat.getColor(this, R.color.red)
        dataSetY.color = ContextCompat.getColor(this, R.color.green)
        dataSetZ.color = ContextCompat.getColor(this, R.color.blue)

        val dataSets = ArrayList<ILineDataSet>().apply {
            add(dataSetX)
            add(dataSetY)
            add(dataSetZ)
        }

        if (chartType == "respeck") {
            allRespeckData = LineData(dataSets)
            respeckChart.data = allRespeckData
            respeckChart.invalidate()
        } else {
            allThingyData = LineData(dataSets)
            thingyChart.data = allThingyData
            thingyChart.invalidate()
        }
    }

    private fun updateGraph(chartType: String, x: Float, y: Float, z: Float) {
        time += 1

        if (chartType == "respeck") {
            updateSlidingWindowGraph("respeck", x, y, z)
        } else {
            updateSlidingWindowGraph("thingy", x, y, z)
        }
    }

    private fun updateSlidingWindowGraph(chartType: String, x: Float, y: Float, z: Float) {
        if (chartType == "respeck") {
            if (allRespeckData.dataSets[0].entryCount >= windowSize) {
                allRespeckData.dataSets[0].removeFirst()
                allRespeckData.dataSets[1].removeFirst()
                allRespeckData.dataSets[2].removeFirst()
            }
            allRespeckData.addEntry(Entry(time, x), 0)
            allRespeckData.addEntry(Entry(time, y), 1)
            allRespeckData.addEntry(Entry(time, z), 2)
            respeckChart.notifyDataSetChanged()
            respeckChart.invalidate()
        } else {
            if (allThingyData.dataSets[0].entryCount >= windowSize) {
                allThingyData.dataSets[0].removeFirst()
                allThingyData.dataSets[1].removeFirst()
                allThingyData.dataSets[2].removeFirst()
            }
            allThingyData.addEntry(Entry(time, x), 0)
            allThingyData.addEntry(Entry(time, y), 1)
            allThingyData.addEntry(Entry(time, z), 2)
            thingyChart.notifyDataSetChanged()
            thingyChart.invalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)
    }
}
