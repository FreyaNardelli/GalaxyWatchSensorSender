// HomeViewModel.kt
package com.example.watchstreamer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watchstreamer.sensor.SensorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds rolling sample windows for the on-screen sensor graphs.
 *
 * All actual streaming (sockets, buffering, retries) now lives in
 * SensorService — this ViewModel's only job is feeding SensorGraphCard.
 */
class HomeViewModel : ViewModel() {

    private val _accelX = MutableStateFlow<List<Float>>(emptyList())
    val accelX = _accelX.asStateFlow()

    private val _accelY = MutableStateFlow<List<Float>>(emptyList())
    val accelY = _accelY.asStateFlow()

    private val _accelZ = MutableStateFlow<List<Float>>(emptyList())
    val accelZ = _accelZ.asStateFlow()

    private val _gyroX = MutableStateFlow<List<Float>>(emptyList())
    val gyroX = _gyroX.asStateFlow()

    private val _gyroY = MutableStateFlow<List<Float>>(emptyList())
    val gyroY = _gyroY.asStateFlow()

    private val _gyroZ = MutableStateFlow<List<Float>>(emptyList())
    val gyroZ = _gyroZ.asStateFlow()

    private val _heartRate = MutableStateFlow<List<Float>>(emptyList())
    val heartRate = _heartRate.asStateFlow()

    init {
        viewModelScope.launch {
            SensorService.sensorFlow.collect { data ->
                _accelX.value = (_accelX.value + data.accelX).takeLast(MAX_SAMPLES)
                _accelY.value = (_accelY.value + data.accelY).takeLast(MAX_SAMPLES)
                _accelZ.value = (_accelZ.value + data.accelZ).takeLast(MAX_SAMPLES)
                _gyroX.value = (_gyroX.value + data.gyroX).takeLast(MAX_SAMPLES)
                _gyroY.value = (_gyroY.value + data.gyroY).takeLast(MAX_SAMPLES)
                _gyroZ.value = (_gyroZ.value + data.gyroZ).takeLast(MAX_SAMPLES)
                _heartRate.value = (_heartRate.value + data.heartRate).takeLast(MAX_SAMPLES)
            }
        }
    }

    companion object {
        private const val MAX_SAMPLES = 50
    }
}