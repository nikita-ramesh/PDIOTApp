package com.specknet.pdiotapp.utils

import android.content.Intent
import android.util.Log
import com.specknet.pdiotapp.R
import com.specknet.pdiotapp.bluetooth.BluetoothSpeckService
import com.specknet.pdiotapp.utils.Constants.Config.RESPECK_UUID
import java.io.IOException
import java.util.Date
import kotlin.math.abs

class RESpeckPacketHandler(
    val speckService: BluetoothSpeckService
) {

    private var last_seq_number = -1
    private var mPhoneTimestampCurrentPacketReceived: Long = -1
    private var mPhoneTimestampLastPacketReceived: Long = -1
    private var mRESpeckTimestampCurrentPacketReceived: Long = -1
    private var mRESpeckTimestampLastPacketReceived: Long = -1
    private var currentSequenceNumberInBatch = 0
    private var mSamplingFrequency: Float = Constants.SAMPLING_FREQUENCY

    private var takeIMU = true

    private var mIsEncryptData = false

    private var patientID: String = ""  // Default empty value
    private var androidID: String = ""  // Default empty value
    private var fwVersion: String = ""  // Default empty value

    // Initialize CSV writers
    private val normalCsvWriter = getNormalCsvFileWriter()
    // Ensure imuCsvWriter is initialized for RESpeckIMUSensorDataCsv
    val imuCsvWriter: SensorDataCsvWriter<RESpeckIMUSensorDataCsv> = getIMUCsvFileWriter()

    // Method to set the androidID
    fun setAndroidID(id: String) {
        androidID = id
    }

    // Method to set the firmware version
    fun setFwVersion(version: String) {
        fwVersion = version
    }

    fun processRESpeckLivePacket(values: ByteArray) {
        val actualPhoneTimestamp = Utils.getUnixTimestamp()
        val r = RESpeckPacketDecoder.V6.decodePacket(values)

        val x = r.batchData[0].acc.x / 9.81f
        val y = r.batchData[0].acc.y / 9.81f
        val z = r.batchData[0].acc.z / 9.81f

        val newRESpeckLiveData = RESpeckLiveData(
            actualPhoneTimestamp,
            r.respeckTimestamp,
            currentSequenceNumberInBatch,
            x, y, z,
            mSamplingFrequency,
            r.battLevel,
            r.chargingStatus,
            r.batchData[0].gyro,
            highFrequency = r.batchData[0].highFrequency
        )

        normalCsvWriter.write(
            RESpeckSensorDataCsv(
                actualPhoneTimestamp,
                r.respeckTimestamp, // Assuming this is the RESpeck timestamp
                currentSequenceNumberInBatch, // Sequence number
                x, y, z // Accelerometer values
            )
        )

        Log.i("LivePacket", "newRespeckLiveData = $newRESpeckLiveData")
        broadcastRespeckLiveData(newRESpeckLiveData)

        currentSequenceNumberInBatch += 1
    }

    fun processRESpeckV6Packet(values: ByteArray, useIMU: Boolean = false) {
        val actualPhoneTimestamp = Utils.getUnixTimestamp()

        val r = if (useIMU) {
            RESpeckPacketDecoder.V6.decodeIMUPacket(values, takeIMU)
        } else {
            RESpeckPacketDecoder.V6.decodePacket(values, 0)
        }

        if (useIMU) takeIMU = !takeIMU

        if (!useIMU && last_seq_number >= 0 && r.seqNumber - last_seq_number != 1) {
            if (r.seqNumber == 0 && last_seq_number == 65535) {
                Log.w("RESpeckPacketHandler", "Respeck seq number wrapped")
            } else {
                Log.w("RESpeckPacketHandler", "Unexpected respeck seq number. Expected: ${last_seq_number + 1}, received: ${r.seqNumber}")
                restartRespeckSamplingFrequency()
            }
        }
        last_seq_number = r.seqNumber

        handleTimestamps(actualPhoneTimestamp, r.respeckTimestamp)

        for ((_, acc, gyro, _, highFrequency) in r.batchData) {
            val x = acc.x / 9.81f
            val y = acc.y / 9.81f
            val z = acc.z / 9.81f

            val interpolatedPhoneTimestamp = interpolateTimestamp(
                mPhoneTimestampLastPacketReceived,
                mPhoneTimestampCurrentPacketReceived,
                currentSequenceNumberInBatch,
                Constants.NUMBER_OF_SAMPLES_PER_BATCH
            )
            val interpolatedRespeckTimestamp = interpolateTimestamp(
                mRESpeckTimestampLastPacketReceived,
                mRESpeckTimestampCurrentPacketReceived,
                currentSequenceNumberInBatch,
                Constants.NUMBER_OF_SAMPLES_PER_BATCH
            )

            val newRESpeckLiveData = RESpeckLiveData(
                interpolatedPhoneTimestamp,
                interpolatedRespeckTimestamp,
                currentSequenceNumberInBatch,
                x, y, z,
                mSamplingFrequency,
                r.battLevel,
                r.chargingStatus,
                gyro,
                MagnetometerReading(0f, 0f, 0f)
            )

            if (useIMU) {
                imuCsvWriter.write(
                    RESpeckIMUSensorDataCsv(
                        interpolatedPhoneTimestamp, // Long
                        x, y, z, // Float (accelerometer data)
                        gyro.x, gyro.y, gyro.z // Float (gyroscope data from `gyro`)
                    )
                )
            } else {
                normalCsvWriter.write(
                    RESpeckSensorDataCsv(
                        interpolatedPhoneTimestamp,  // Long (phone timestamp)
                        mRESpeckTimestampCurrentPacketReceived, // Long (RESpeck timestamp)
                        currentSequenceNumberInBatch, // Int (sequence number)
                        x, y, z // Float (accelerometer data)
                    )
                )
            }

            Log.i("Freq", "newRespeckLiveData = $newRESpeckLiveData")
            broadcastRespeckLiveData(newRESpeckLiveData)

            currentSequenceNumberInBatch += 1
        }
    }

    // For normal CSV writer
    private fun getNormalCsvFileWriter(): SensorDataCsvWriter<RESpeckSensorDataCsv> =
        getCsvFileWriter(
            Constants.RESPECK_DATA_DIRECTORY_NAME,
            RESpeckSensorDataCsv.csvHeader
        )

    // For IMU CSV writer
    private fun getIMUCsvFileWriter(): SensorDataCsvWriter<RESpeckIMUSensorDataCsv> =
        getCsvFileWriter(
            Constants.RESPECK_IMU_DATA_DIRECTORY_NAME,
            RESpeckIMUSensorDataCsv.csvHeader
        )

    // Generic function to get the CSV file writer
    private fun <T : CsvSerializable> getCsvFileWriter(
        dir: String,
        header: String
    ): SensorDataCsvWriter<T> {
        // Check whether we are in a new day
        val now = Date()

        // Format the filename based on parameters
        val filename = "./" +
                dir +
                listOf(
                    speckService.getString(R.string.respeck_name),
                    patientID,
                    androidID,
                    "${RESPECK_UUID.replace(":", "")}($fwVersion)",
                    Constants.dateFormatter.format(now)
                ).joinToString(" ")

        // Return the appropriate CsvWriter instance for the specific type
        return SensorDataCsvWriter(
            filename,
            header,
            speckService.applicationContext, // Assuming this is needed for encryption
            mIsEncryptData
        )
    }

    private fun handleTimestamps(actualPhoneTimestamp: Long, respeckTimestamp: Long) {
        // Handle phone timestamps for interpolation
        mPhoneTimestampLastPacketReceived = if (mPhoneTimestampCurrentPacketReceived == -1L ||
            mPhoneTimestampCurrentPacketReceived + 2.5 * Constants.AVERAGE_TIME_DIFFERENCE_BETWEEN_RESPECK_PACKETS < actualPhoneTimestamp) {
            actualPhoneTimestamp - Constants.AVERAGE_TIME_DIFFERENCE_BETWEEN_RESPECK_PACKETS
        } else {
            mPhoneTimestampCurrentPacketReceived
        }

        val extrapolatedPhoneTimestamp = mPhoneTimestampLastPacketReceived + Constants.AVERAGE_TIME_DIFFERENCE_BETWEEN_RESPECK_PACKETS

        mPhoneTimestampCurrentPacketReceived = if (abs(extrapolatedPhoneTimestamp - actualPhoneTimestamp) >
            Constants.MAXIMUM_MILLISECONDS_DEVIATION_ACTUAL_AND_CORRECTED_TIMESTAMP) {
            actualPhoneTimestamp
        } else {
            extrapolatedPhoneTimestamp
        }

        // Handle RESpeck timestamps for interpolation
        mRESpeckTimestampLastPacketReceived = if (mRESpeckTimestampCurrentPacketReceived == -1L) {
            respeckTimestamp - Constants.AVERAGE_TIME_DIFFERENCE_BETWEEN_RESPECK_PACKETS
        } else {
            mRESpeckTimestampCurrentPacketReceived
        }

        mRESpeckTimestampCurrentPacketReceived = respeckTimestamp
    }


    private fun interpolateTimestamp(lastTs: Long, currentTs: Long, sequenceNum: Int, totalSamples: Int): Long {
        return ((currentTs - lastTs) * (sequenceNum * 1.0 / totalSamples)).toLong() + lastTs
    }

    private fun broadcastRespeckLiveData(data: RESpeckLiveData) {
        val liveDataIntent = Intent(Constants.ACTION_RESPECK_LIVE_BROADCAST)
        liveDataIntent.putExtra(Constants.RESPECK_LIVE_DATA, data)
        speckService.sendBroadcast(liveDataIntent)
    }

    private fun restartRespeckSamplingFrequency() {
        Log.w("RESpeckPacketHandler", "Restarting sampling frequency due to packet loss.")
    }

    fun closeHandler() {
        try {
            Log.i("RESpeckPacketHandler", "Closing CSV writers")
            normalCsvWriter.close()
            imuCsvWriter.close()
        } catch (e: IOException) {
            Log.e("RESpeckPacketHandler", "Error closing CSV writers: ${e.message}")
        }
    }
}
