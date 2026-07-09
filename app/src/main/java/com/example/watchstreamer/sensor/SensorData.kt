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
    val heartRate: Float = 0f
) {
    /**
     * Serializes this reading as a JSON list for the receiver, e.g.
     * [1.1, 2.2, 3.3, 4.4, 5.5, 6.6, 77.0]
     *
     * Order: accelX, accelY, accelZ, gyroX, gyroY, gyroZ, heartRate
     */
//    fun toListPayloadString(): String {
//        return "[${accelX}, ${accelY}, ${accelZ}, ${gyroX}, ${gyroY}, ${gyroZ}, ${heartRate}]"
//    }
    fun toListPayloadString(): String {
        return "[${timestamp}, ${accelX}, ${accelY}, ${accelZ}, ${gyroX}, ${gyroY}, ${gyroZ}, ${heartRate}]"
    }

    fun toPayloadString(): String = Json.encodeToString(this)
}
