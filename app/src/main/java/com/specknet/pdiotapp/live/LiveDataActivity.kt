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
import com.specknet.pdiotapp.database.AppDatabase
import com.specknet.pdiotapp.database.dao.ActivityLogDao
import com.specknet.pdiotapp.database.entities.ActivityLogEntry
import java.util.concurrent.Executors

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

    var xRespeck = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }
    var yRespeck = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }
    var zRespeck = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }

    var xThingy = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }
    var yThingy = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }
    var zThingy = MutableList(Constants.SLIDING_WINDOW_SIZE) { 0f }

    // TensorFlow Lite Interpreter for Activity Classification
    private lateinit var tflite: Interpreter
    private lateinit var socialSignalTflite: Interpreter

    // Buffers for Respeck and Thingy data
    private var respeckData = FloatArray(Constants.SLIDING_WINDOW_SIZE * 3)
    private var thingyData = FloatArray(Constants.SLIDING_WINDOW_SIZE * 3)

    private var respeckSampleCount = 0
    private var thingySampleCount = 0

    // for history/log functionality:
    private lateinit var db: AppDatabase
    private lateinit var activityLogDao: ActivityLogDao
    private var currentActivity: String? = null
    private var activityStartTime: Long = 0L
    private val executorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)

        // for history/log functionality: Initialize the database and DAO
        db = AppDatabase.getDatabase(this)
        activityLogDao = db.activityLogDao()
        // for history/log functionality: Initialize activity start time
        activityStartTime = System.currentTimeMillis()

        // Load TensorFlow Lite model
        loadModel()
        loadSocialSignalModel()
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
        if (respeckSampleCount >= Constants.SLIDING_WINDOW_SIZE && thingySampleCount >= Constants.SLIDING_WINDOW_SIZE) {
            classifyActivity()
            classifySocialSignal()
            // Reset step counts after running classification so new windows can be collected
            respeckSampleCount = 0
            thingySampleCount = 0
            // Optionally clear sliding windows if you want to start fresh each time:
            xRespeck.clear(); yRespeck.clear(); zRespeck.clear()
            xThingy.clear(); yThingy.clear(); zThingy.clear()
            // Reinitialize with zeros or empty values for next collection round:
            repeat(Constants.SLIDING_WINDOW_SIZE) {
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

    private fun loadSocialSignalModel() {
        val modelPath = "social_signals_gmodel1.tflite" // Social signal model
        try {
            val assetManager = assets
            val model = FileUtil.loadMappedFile(this, modelPath)
            socialSignalTflite = Interpreter(model)
            Log.d("LiveDataActivity", "Social signal model loaded successfully")
        } catch (e: Exception) {
            Log.e("LiveDataActivity", "Error loading social signal model", e)
        }
    }

    private fun classifyActivity() {
        if (!::tflite.isInitialized) {
            Log.e("LiveDataActivity", "Model not initialized")
            return
        }

        try {
            // Prepare input tensors for Respeck and Thingy data
            val respeckInput = TensorBuffer.createFixedSize(intArrayOf(1, Constants.SLIDING_WINDOW_SIZE, 3), DataType.FLOAT32)
            respeckInput.loadArray(respeckData)

            val thingyInput = TensorBuffer.createFixedSize(intArrayOf(1, Constants.SLIDING_WINDOW_SIZE, 3), DataType.FLOAT32)
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

    private fun classifySocialSignal() {
        if (!::socialSignalTflite.isInitialized) {
            Log.e("LiveDataActivity", "Social signal model not initialized")
            return
        }

        try {
            Log.d("LiveDataActivity", "Starting classifySocialSignal")
            // Prepare input tensor for Respeck data
            val respeckInput = TensorBuffer.createFixedSize(
                intArrayOf(1, Constants.SLIDING_WINDOW_SIZE, 3), DataType.FLOAT32
            )
            respeckInput.loadArray(respeckData)

            // Prepare output tensor (assuming there are N social signal classes)
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 4), DataType.FLOAT32) // Replace N with the actual number of classes

            // Run inference
            socialSignalTflite.run(respeckInput.buffer, outputBuffer.buffer)

            // Process the result
            val result = outputBuffer.floatArray
            Log.d("LiveDataActivity", "Social signal classification result: ${result.contentToString()}")
            val maxIndex = result.indexOfFirst { it == result.maxOrNull() ?: -1f }
            updateSocialSignalResult(maxIndex)
        } catch (e: Exception) {
            Log.e("LiveDataActivity", "Error during social signal classification", e)
        }
    }

    private fun updateClassificationResult(index: Int) {
        // Get the activity label using the index
        val activityLabel = getActivityFromIndex(index)

        // Update the classification view with the activity result
        val classificationView: TextView = findViewById(R.id.classification_label)
        classificationView.text = "Activity: $activityLabel"
        val currentTime = System.currentTimeMillis()
        if (currentActivity == null) {
            // First activity detected
            currentActivity = activityLabel
            activityStartTime = currentTime
        } else if (currentActivity != activityLabel) {
            // Activity has changed
            val activityEndTime = currentTime
            // Log the previous activity
            val entry = ActivityLogEntry(
                activityName = currentActivity!!,
                startTime = activityStartTime,
                endTime = activityEndTime
            )
            // Insert into database in the background
            executorService.execute {
                activityLogDao.insert(entry)
            }
            // Update current activity and start time
            currentActivity = activityLabel
            activityStartTime = currentTime
        }
        // Else, activity is the same, do nothing
    }

    private fun updateSocialSignalResult(index: Int) {
        // Get the social signal label using the index
        val socialSignalLabel = getSocialSignalFromIndex(index)

        // Update the social signal TextView
        val socialSignalView: TextView = findViewById(R.id.social_signal_label)
        socialSignalView.text = "Social Signal: $socialSignalLabel"
    }

    fun updateSlidingWindow(list: MutableList<Float>, newValue: Float, buffer: FloatArray, featureIndex: Int) {
        if (list.size < Constants.SLIDING_WINDOW_SIZE) {
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

    fun getSocialSignalFromIndex(index: Int): String {
        return when (index) {
            0 -> "Breathing Normally"
            1 -> "Coughing"
            2 -> "Hyperventilation"
            3 -> "Other"
            else -> "Other"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)

        // Log the last activity if any
        if (currentActivity != null) {
            val activityEndTime = System.currentTimeMillis()
            val entry = ActivityLogEntry(
                activityName = currentActivity!!,
                startTime = activityStartTime,
                endTime = activityEndTime
            )
            executorService.execute {
                activityLogDao.insert(entry)
            }
        }
        executorService.shutdown()
    }
}
