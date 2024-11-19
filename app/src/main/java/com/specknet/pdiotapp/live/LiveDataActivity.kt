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

    private var combinedData = FloatArray(600) // 100 samples * 6 features


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)
        val classificationView: TextView = findViewById(R.id.classification_label)

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

                    updateSlidingWindow(xRespeck, x, combinedData, 0)
                    updateSlidingWindow(yRespeck, y, combinedData, 1)
                    updateSlidingWindow(zRespeck, z, combinedData, 2)

                    time += 1
                    updateGraph("respeck", x, y, z)
                    classifyActivity()
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
                if (action == Constants.ACTION_THINGY_BROADCAST)  {
                    val liveData = intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    updateSlidingWindow(xThingy, x, combinedData, 3)
                    updateSlidingWindow(yThingy, y, combinedData, 4)
                    updateSlidingWindow(zThingy, z, combinedData, 5)

                    time += 1
                    updateGraph("thingy", x, y, z)
                    classifyActivity()
                }
            }
        }

        val handlerThreadThingy = HandlerThread("bgThreadThingyLive")
        handlerThreadThingy.start()
        looperThingy = handlerThreadThingy.looper
        val handlerThingy = Handler(looperThingy)
        this.registerReceiver(thingyLiveUpdateReceiver, filterTestThingy, null, handlerThingy)
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
        val modelPath = "model.tflite"  // Replace with your model's path
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
            // Prepare the input tensor for classification
            val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 600), DataType.FLOAT32)
            inputBuffer.loadArray(combinedData)

            // Run the model
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 6), DataType.FLOAT32)
            tflite.run(inputBuffer.buffer, outputBuffer.buffer)

            // Process the result (assuming it’s a classification task)
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

    fun updateSlidingWindow(list: MutableList<Float>, newValue: Float, combinedData: FloatArray, dataIndex: Int) {
        list.removeAt(0) // Remove the oldest element
        list.add(newValue) // Append the new value

        // Update the combinedData at the appropriate index
        for (i in list.indices) {
            combinedData[i * 6 + dataIndex] = list[i]
        }
    }

    fun getActivityFromIndex(index: Int): String {
        return when (index) {
            0 -> "SittingStanding"
            1 -> "lyingBack"
            2 -> "lyingLeft"
            3 -> "lyingRight"
            4 -> "lyingStomach"
            5 -> "miscMovement"
            6 -> "normalWalking"
            7 -> "running"
            8 -> "shuffleWalking"
            9 -> "ascending"
            10 -> "descending"
            else -> "miscMovement"

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)
    }
}
