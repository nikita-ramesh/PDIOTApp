package com.specknet.pdiotapp.utils

import android.content.Intent
import android.util.Log
import com.specknet.pdiotapp.bluetooth.BluetoothSpeckService
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles Thingy data processing and broadcasting.
 */
class ThingyPacketHandler(private val speckService: BluetoothSpeckService) {

    private val TAG = "ThingyPacketHandler"

    // Buffer to store the latest Thingy accelerometer data
    private val thingyDataBuffer: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    // Process a Thingy packet
    fun processThingyPacket(values: ByteArray) {
        val actualPhoneTimestamp = Utils.getUnixTimestamp()

        // Decode the Thingy packet
        val thingyData = ThingyPacketDecoder.decodeThingyPacket(values)

        Log.d(TAG, "processThingyPacket: decoded data $thingyData")

        // Normalize accelerometer data
        val accelX = thingyData.accelData.x / 9.81f
        val accelY = thingyData.accelData.y / 9.81f
        val accelZ = thingyData.accelData.z / 9.81f

        // Add processed accelerometer data to the buffer
        thingyDataBuffer.offer(floatArrayOf(accelX, accelY, accelZ))

        // Remove excess entries from the buffer to maintain a fixed size
        while (thingyDataBuffer.size > Constants.SLIDING_WINDOW_SIZE) {
            thingyDataBuffer.poll()
        }

        // Send live broadcast intent with preprocessed data
        val liveDataIntent = Intent(Constants.ACTION_THINGY_BROADCAST)
        liveDataIntent.putExtra(Constants.THINGY_LIVE_DATA, ThingyLiveData(
            actualPhoneTimestamp,
            accelX, accelY, accelZ,
            thingyData.gyroData,
            thingyData.magData
        ))
        speckService.sendBroadcast(liveDataIntent)
    }

    // Expose the latest buffered Thingy data
    fun getBufferedData(): List<FloatArray> {
        return thingyDataBuffer.toList()
    }
}
