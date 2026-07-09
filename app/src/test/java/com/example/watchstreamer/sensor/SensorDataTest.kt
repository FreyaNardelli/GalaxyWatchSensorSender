package com.example.watchstreamer.sensor

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDataTest {
    @Test
    fun toPayloadString_roundTripsAllFields() {
        val data = SensorData(
            timestamp = 1000L,
            accelX = 1.1f,
            accelY = 2.2f,
            accelZ = 3.3f,
            gyroX = 4.4f,
            gyroY = 5.5f,
            gyroZ = 6.6f,
            heartRate = 77f
        )

        val payload = data.toPayloadString()
        val decoded = Json.decodeFromString<SensorData>(payload)

        assertEquals(data, decoded)
    }

    @Test
    fun toListPayloadString_isCorrect() {
        val data = SensorData(
            accelX = 1.1f,
            accelY = 2.2f,
            accelZ = 3.3f,
            gyroX = 4.4f,
            gyroY = 5.5f,
            gyroZ = 6.6f,
            heartRate = 77f
        )
        val expected = "[1.1, 2.2, 3.3, 4.4, 5.5, 6.6, 77.0]"
        assertEquals(expected, data.toListPayloadString())
    }

    @Test
    fun toPayloadString_usesNamedJsonFields() {
        val data = SensorData(accelX = 1.1f, heartRate = 77f)
        val payload = data.toPayloadString()

        // Fields are self-describing keys, not a bare positional list.
        assertTrue(payload.contains("\"accelX\":1.1"))
        assertTrue(payload.contains("\"heartRate\":77.0"))
    }
}
