package com.specknet.pdiotapp.utils

import android.content.Intent
import android.util.Log
import com.specknet.pdiotapp.bluetooth.BluetoothSpeckService
import kotlin.math.abs

/**
 * This class processes new RESpeck packets which are passed from the SpeckBluetoothService.
 * It contains all logic to transform the incoming bytes into the desired variables and then stores and broadcasts
 * this information.
 */
class RESpeckPacketHandler(val speckService: BluetoothSpeckService) {

    private var last_seq_number = -1
    private var mPhoneTimestampCurrentPacketReceived: Long = -1
    private var mPhoneTimestampLastPacketReceived: Long = -1
    private var mRESpeckTimestampCurrentPacketReceived: Long = -1
    private var mRESpeckTimestampLastPacketReceived: Long = -1
    private var currentSequenceNumberInBatch = 0
    private var mSamplingFrequency: Float = Constants.SAMPLING_FREQUENCY

    // Flag to alternate between taking normal and IMU packets
    private var takeIMU = true

    fun processRESpeckV6Packet(values: ByteArray, useIMU: Boolean = false) {
        Log.d("BLT", "processRESpeckV6Packet: here")

        // Independent of the RESpeck timestamp, we use the phone timestamp
        val actualPhoneTimestamp = Utils.getUnixTimestamp()

        // Decode either normal or IMU packet based on the flag `useIMU`
        val r = if (useIMU) {
            RESpeckPacketDecoder.V6.decodeIMUPacket(values, takeIMU)
        } else {
            RESpeckPacketDecoder.V6.decodePacket(values, 0)
        }

        // Invert `takeIMU` to alternate between packets if in IMU mode
        if (useIMU) {
            takeIMU = !takeIMU
        }

        // Sequence number handling for non-IMU packets (IMU packets may not have sequence numbers)
        if (!useIMU && last_seq_number >= 0 && r.seqNumber - last_seq_number != 1) {
            if (r.seqNumber == 0 && last_seq_number == 65535) {
                Log.w("RESpeckPacketHandler", "Respeck seq number wrapped")
            } else {
                Log.w("RESpeckPacketHandler", "Unexpected respeck seq number. Expected: ${last_seq_number + 1}, received: ${r.seqNumber}")
                restartRespeckSamplingFrequency()
            }
        }
        last_seq_number = r.seqNumber

        // Handle timestamps for phone and RESpeck device
        handleTimestamps(actualPhoneTimestamp, r.respeckTimestamp)

        // Process each sample in the batch (e.g., 32 samples per batch)
        for ((_, acc, gyro, _, highFrequency) in r.batchData) {
            val x = acc.x / 9.81f  // Normalize accelerometer data (convert to G-forces)
            val y = acc.y / 9.81f
            val z = acc.z / 9.81f

            // Interpolate timestamps for each sample in the batch
            val interpolatedPhoneTimestampOfCurrentSample = interpolateTimestamp(
                mPhoneTimestampLastPacketReceived,
                mPhoneTimestampCurrentPacketReceived,
                currentSequenceNumberInBatch,
                Constants.NUMBER_OF_SAMPLES_PER_BATCH
            )
            val interpolatedRespeckTimestampOfCurrentSample = interpolateTimestamp(
                mRESpeckTimestampLastPacketReceived,
                mRESpeckTimestampCurrentPacketReceived,
                currentSequenceNumberInBatch,
                Constants.NUMBER_OF_SAMPLES_PER_BATCH
            )

            // Create new RESpeckLiveData object with preprocessed values (including gyro data)
            val newRESpeckLiveData = RESpeckLiveData(
                interpolatedPhoneTimestampOfCurrentSample,
                interpolatedRespeckTimestampOfCurrentSample,
                currentSequenceNumberInBatch,
                x, y, z,
                mSamplingFrequency,
                r.battLevel,
                r.chargingStatus,
                gyro,
                highFrequency = highFrequency
            )

            Log.i("Freq", "newRespeckLiveData = $newRESpeckLiveData")

            // Broadcast preprocessed data
            broadcastRespeckLiveData(newRESpeckLiveData)

            currentSequenceNumberInBatch += 1
        }
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
        // Send live broadcast intent with preprocessed data
        val liveDataIntent = Intent(Constants.ACTION_RESPECK_LIVE_BROADCAST)
        liveDataIntent.putExtra(Constants.RESPECK_LIVE_DATA, data)
        speckService.sendBroadcast(liveDataIntent)
    }

    private fun restartRespeckSamplingFrequency() {
        Log.w("RESpeckPacketHandler", "Restarting sampling frequency due to packet loss.")
    }
}