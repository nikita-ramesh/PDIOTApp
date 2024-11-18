package com.specknet.pdiotapp.utils

import android.content.Intent
import android.util.Log
import com.specknet.pdiotapp.bluetooth.BluetoothSpeckService

/**
 * This class processes new Thingy packets which are passed from the SpeckBluetoothService.
 * It contains all logic to transform the incoming bytes into the desired variables and then stores and broadcasts
 * this information.
 */
class ThingyPacketHandler(val speckService: BluetoothSpeckService) {

    private val TAG = "ThingyPacketHandler"

    var fwVersion: String = speckService.reSpeckFwVersion
        set(value) {
            field = value
        }

    // Buffer for sliding window (if needed)
    private val thingyBuffer: ArrayDeque<FloatArray> = ArrayDeque()

    // Process a Thingy packet
    fun processThingyPacket(values: ByteArray) {
        val actualPhoneTimestamp = Utils.getUnixTimestamp()

        // Decode the Thingy packet
        val thingyData = ThingyPacketDecoder.decodeThingyPacket(values)

        Log.d(TAG, "processThingyPacket: decoded data $thingyData")

        // Preprocess accelerometer data (normalize by dividing by 9.81 to convert to G-forces)
        val accelX = thingyData.accelData.x / 9.81f
        val accelY = thingyData.accelData.y / 9.81f
        val accelZ = thingyData.accelData.z / 9.81f

        // Add new sample to buffer (if you're using sliding windows)
        thingyBuffer.add(floatArrayOf(accelX, accelY, accelZ))

        // You can apply sliding window processing here if needed (e.g., after collecting enough samples)

        // Create new ThingyLiveData object with preprocessed values
        val newThingyLiveData = ThingyLiveData(
            actualPhoneTimestamp,
            accelX,
            accelY,
            accelZ,
            thingyData.gyroData,  // Gyroscope data (already decoded)
            thingyData.magData     // Magnetometer data (already decoded)
        )

        Log.i("Freq", "newThingyLiveData = $newThingyLiveData")

        // Send live broadcast intent with preprocessed data
        val liveDataIntent = Intent(Constants.ACTION_THINGY_BROADCAST)
        liveDataIntent.putExtra(Constants.THINGY_LIVE_DATA, newThingyLiveData)
        speckService.sendBroadcast(liveDataIntent)
    }
}