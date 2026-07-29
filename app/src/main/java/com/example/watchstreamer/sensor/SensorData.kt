// SensorData.kt

package com.example.watchstreamer.sensor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SensorData(
    val timestamp: Long = System.currentTimeMillis(),
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val heartRate: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val imuRateHz: Int = 0,
    val sendRateHz: Int = 0
) {
    /**
     * Serializes this reading as a JSON list for the receiver, e.g.
     * [1752600000000, 1.1, 2.2, 3.3, 4.4, 5.5, 6.6, 77.0, 37.422, -122.084, 50, 50]
     *
     * Order: timestamp, accelX, accelY, accelZ, gyroX, gyroY, gyroZ, heartRate,
     *        latitude, longitude, imuRateHz, sendRateHz
     *
     * The two rate fields were appended at the end (rather than inserted earlier
     * in the list) so any existing receiver-side parser that only reads the
     * first 10 positional fields keeps working unchanged.
     */
//    fun toListPayloadString(): String {
//        return "[${accelX}, ${accelY}, ${accelZ}, ${gyroX}, ${gyroY}, ${gyroZ}, ${heartRate}]"
//    }
    fun toListPayloadString(): String {
        return "[${timestamp}, ${accelX}, ${accelY}, ${accelZ}, ${gyroX}, ${gyroY}, ${gyroZ}, ${heartRate}, ${latitude}, ${longitude}, ${imuRateHz}, ${sendRateHz}]"
    }

    fun toPayloadString(): String = Json.encodeToString(this)
}
