package com.specknet.pdiotapp.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class LiveDataActivity : AppCompatActivity() {

    lateinit var dataSet_res_accel_x: LineDataSet
    lateinit var dataSet_res_accel_y: LineDataSet
    lateinit var dataSet_res_accel_z: LineDataSet

    lateinit var dataSet_thingy_accel_x: LineDataSet
    lateinit var dataSet_thingy_accel_y: LineDataSet
    lateinit var dataSet_thingy_accel_z: LineDataSet

    var time = 0f

    lateinit var allRespeckData: LineData
    lateinit var allThingyData: LineData

    lateinit var respeckChart: LineChart
    lateinit var thingyChart: LineChart

    lateinit var respeckLiveUpdateReceiver: BroadcastReceiver
    lateinit var thingyLiveUpdateReceiver: BroadcastReceiver

    lateinit var looperRespeck: Looper
    lateinit var looperThingy: Looper

    val filterTestRespeck = IntentFilter(Constants.ACTION_RESPECK_LIVE_BROADCAST)
    val filterTestThingy = IntentFilter(Constants.ACTION_THINGY_BROADCAST)

    var xRespeck = MutableList(100) { 0f }
    var yRespeck = MutableList(100) { 0f }
    var zRespeck = MutableList(100) { 0f }

    var xThingy = MutableList(100) { 0f }
    var yThingy = MutableList(100) { 0f }
    var zThingy = MutableList(100) { 0f }

    // TensorFlow Lite Interpreter for Activity Classification
    private lateinit var tflite: Interpreter

    // Buffers for Respeck and Thingy data
    private var respeckData = FloatArray(300) // 100 timesteps * 3 features (X, Y, Z)
    private var thingyData = FloatArray(300)  // 100 timesteps * 3 features (X, Y, Z)

    private var respeckSampleCount = 0
    private var thingySampleCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)

        // Load TensorFlow Lite model
        loadModel()
        setupCharts()

        // Respeck Receiver
        respeckLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == Constants.ACTION_RESPECK_LIVE_BROADCAST) {
                    val liveData = intent.getSerializableExtra(Constants.RESPECK_LIVE_DATA) as RESpeckLiveData
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    updateSlidingWindow(xRespeck, x, respeckData, 0)
                    updateSlidingWindow(yRespeck, y, respeckData, 1)
                    updateSlidingWindow(zRespeck, z, respeckData, 2)
                    respeckSampleCount++

                    time += 1
                    updateGraph("respeck", x, y, z)
                    checkAndClassifyActivity()
                }
            }
        }

        val handlerThreadRespeck = HandlerThread("bgThreadRespeckLive")
        handlerThreadRespeck.start()
        looperRespeck = handlerThreadRespeck.looper
        val handlerRespeck = Handler(looperRespeck)
        this.registerReceiver(respeckLiveUpdateReceiver, filterTestRespeck, null, handlerRespeck)

        // Thingy Receiver
        thingyLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == Constants.ACTION_THINGY_BROADCAST) {
                    val liveData = intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    updateSlidingWindow(xThingy, x, thingyData, 0)
                    updateSlidingWindow(yThingy, y, thingyData, 1)
                    updateSlidingWindow(zThingy, z, thingyData, 2)
                    thingySampleCount++

                    time += 1
                    updateGraph("thingy", x, y, z)
                    checkAndClassifyActivity()
                }
            }
        }

        val handlerThreadThingy = HandlerThread("bgThreadThingyLive")
        handlerThreadThingy.start()
        looperThingy = handlerThreadThingy.looper
        val handlerThingy = Handler(looperThingy)
        this.registerReceiver(thingyLiveUpdateReceiver, filterTestThingy, null, handlerThingy)
    }

    // Function to check if both sensors have collected enough data before running classification
    private fun checkAndClassifyActivity() {
        // Ensure both Respeck and Thingy have received 100 new steps before running classification
        if (respeckSampleCount >= 100 && thingySampleCount >= 100) {
            classifyActivity()
            // Reset step counts after running classification so new windows can be collected
            respeckSampleCount = 0
            thingySampleCount = 0
            // Optionally clear sliding windows if you want to start fresh each time:
            xRespeck.clear(); yRespeck.clear(); zRespeck.clear()
            xThingy.clear(); yThingy.clear(); zThingy.clear()
            // Reinitialize with zeros or empty values for next collection round:
            repeat(100) {
                xRespeck.add(0f); yRespeck.add(0f); zRespeck.add(0f)
                xThingy.add(0f); yThingy.add(0f); zThingy.add(0f)
            }
        } else {
            Log.d("Sync", "Waiting for both sensors to provide enough samples...")
        }
    }

    fun setupCharts() {
        respeckChart = findViewById(R.id.respeck_chart)
        thingyChart = findViewById(R.id.thingy_chart)

        time = 0f

        val entries_res_accel_x = ArrayList<Entry>()
        val entries_res_accel_y = ArrayList<Entry>()
        val entries_res_accel_z = ArrayList<Entry>()

        dataSet_res_accel_x = LineDataSet(entries_res_accel_x, "Accel X")
        dataSet_res_accel_y = LineDataSet(entries_res_accel_y, "Accel Y")
        dataSet_res_accel_z = LineDataSet(entries_res_accel_z, "Accel Z")

        dataSet_res_accel_x.setDrawCircles(false)
        dataSet_res_accel_y.setDrawCircles(false)
        dataSet_res_accel_z.setDrawCircles(false)

        dataSet_res_accel_x.color = ContextCompat.getColor(this, R.color.red)
        dataSet_res_accel_y.color = ContextCompat.getColor(this, R.color.green)
        dataSet_res_accel_z.color = ContextCompat.getColor(this, R.color.blue)

        val dataSetsRes = ArrayList<ILineDataSet>()
        dataSetsRes.add(dataSet_res_accel_x)
        dataSetsRes.add(dataSet_res_accel_y)
        dataSetsRes.add(dataSet_res_accel_z)

        allRespeckData = LineData(dataSetsRes)
        respeckChart.data = allRespeckData
        respeckChart.invalidate()
        respeckChart.legend.isEnabled = false

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
        thingyChart.invalidate()
        thingyChart.legend.isEnabled = false
    }

    fun updateGraph(graph: String, x: Float, y: Float, z: Float) {
        if (graph == "respeck") {
            dataSet_res_accel_x.addEntry(Entry(time, x))
            dataSet_res_accel_y.addEntry(Entry(time, y))
            dataSet_res_accel_z.addEntry(Entry(time, z))
            allRespeckData.notifyDataChanged()
            respeckChart.notifyDataSetChanged()
            respeckChart.invalidate()
        } else if (graph == "thingy") {
            dataSet_thingy_accel_x.addEntry(Entry(time, x))
            dataSet_thingy_accel_y.addEntry(Entry(time, y))
            dataSet_thingy_accel_z.addEntry(Entry(time, z))
            allThingyData.notifyDataChanged()
            thingyChart.notifyDataSetChanged()
            thingyChart.invalidate()
        }
    }

    private fun loadModel() {
        val modelPath = "activities_model.tflite"  // Replace with your model's path
        try {
            val assetManager = assets
            val model = FileUtil.loadMappedFile(this, modelPath)
            tflite = Interpreter(model)
            Log.d("LiveDataActivity", "TensorFlow Lite model loaded successfully")
        } catch (e: Exception) {
            Log.e("LiveDataActivity", "Error loading TensorFlow Lite model", e)
        }
    }

    private fun classifyActivity() {
        if (!::tflite.isInitialized) {
            Log.e("LiveDataActivity", "Model not initialized")
            return
        }

        try {
            // Prepare input tensors for Respeck and Thingy data
            val respeckInput = TensorBuffer.createFixedSize(intArrayOf(1, 100, 3), DataType.FLOAT32)
            respeckInput.loadArray(respeckData)

            val thingyInput = TensorBuffer.createFixedSize(intArrayOf(1, 100, 3), DataType.FLOAT32)
            thingyInput.loadArray(thingyData)

            // Prepare output tensor (assuming there are 11 classes)
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 11), DataType.FLOAT32)

            // Run inference with both inputs
            val inputs = arrayOf(respeckInput.buffer, thingyInput.buffer)
            tflite.runForMultipleInputsOutputs(inputs, mapOf(0 to outputBuffer.buffer))

            // Process the result
            val result = outputBuffer.floatArray
            val maxIndex = result.indexOfFirst { it == result.maxOrNull() ?: -1f }
            updateClassificationResult(maxIndex)
        } catch (e: Exception) {
            Log.e("LiveDataActivity", "Error during classification", e)
        }
    }

    private fun updateClassificationResult(index: Int) {
        // Get the activity label using the index
        val activityLabel = getActivityFromIndex(index)

        // Update the classification view with the activity result
        val classificationView: TextView = findViewById(R.id.classification_label)
        classificationView.text = "Activity: $activityLabel"
    }

    fun updateSlidingWindow(list: MutableList<Float>, newValue: Float, buffer: FloatArray, featureIndex: Int) {
        if (list.size < 100) {
            Log.e("LiveDataActivity", "List size is smaller than expected!")
            return
        }

        list.removeAt(0) // Remove oldest value
        list.add(newValue) // Add new value

        // Update buffer at appropriate index
        for (i in list.indices) {
            if (i * 3 + featureIndex < buffer.size) {
                buffer[i * 3 + featureIndex] = list[i]
            } else {
                Log.e("LiveDataActivity", "Buffer index out of bounds!")
            }
        }
    }

    fun getActivityFromIndex(index: Int): String {
        return when (index) {
            0 -> "Sitting / Standing"
            1 -> "Lying on Back"
            2 -> "Lying on Left"
            3 -> "Lying on Right"
            4 -> "Lying on Stomach"
            5 -> "Miscellaneous Movement"
            6 -> "Normal Walking"
            7 -> "Running"
            8 -> "Shuffle Walking"
            9 -> "Ascending"
            10 -> "Descending"
            else -> "Miscellaneous Movement"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)
    }
}
