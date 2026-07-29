// FirebaseUploader.kt

package com.example.watchstreamer.sensor

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pushes sensor readings to Firebase Realtime Database on two paths:
 *
 *  • sensorData/latest   — overwritten at 2 Hz; used for real-time display
 *                          and as the iPhone's UDP fallback channel.
 *  • sensorData/history  — a new child appended at 1 Hz using push(), so
 *                          every record is preserved with a unique key.
 *                          The dashboard and Firebase console can browse
 *                          the full session log here.
 *
 * This runs *in addition to* the UDP sender — it does not replace it.
 * The Firebase SDK buffers writes locally and retries on reconnect, so
 * short connectivity gaps don't lose any history records.
 */


class FirebaseUploader(
    private val scope: CoroutineScope,
    private val latestIntervalMs: Long  = 500L,    // 2 Hz  — live / fallback
    private val historyIntervalMs: Long = 1000L    // 1 Hz  — permanent log
) {
    private val db = FirebaseDatabase.getInstance()

    // Overwritten on every latestIntervalMs tick.
    private val latestRef: DatabaseReference = db.getReference("sensorData/latest")

    // push() appends a new child with a unique timestamp-based key each tick.
    private val historyRef: DatabaseReference = db.getReference("sensorData/history")

    @Volatile private var latestData: SensorData? = null
    private var latestJob: Job? = null
    private var historyJob: Job? = null

    /** Accept the newest reading. Safe to call from any thread at 10 Hz. */
    fun onNewData(data: SensorData) {
        latestData = data
    }

    /** Begin uploads on both channels. Idempotent. */
    fun start() {
        startLatest()
        startHistory()
    }

    private fun startLatest() {
        if (latestJob?.isActive == true) return
        latestJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                latestData?.let { data ->
                    latestRef.setValue(buildPayload(data))
                }
                delay(latestIntervalMs)
            }
        }
    }

    private fun startHistory() {
        if (historyJob?.isActive == true) return
        historyJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                latestData?.let { data ->
                    // push() creates a unique child key (e.g. -NxK8abc…)
                    // ordered by server timestamp, so records are always
                    // browsable in chronological order in the console.
                    historyRef.push().setValue(buildPayload(data))
                }
                delay(historyIntervalMs)
            }
        }
    }

    /** Shared payload map — same fields on both paths for consistency. */
    private fun buildPayload(data: SensorData): Map<String, Any> = mapOf(

                            "timestamp"  to data.timestamp,
                            "accelX"     to data.accelX,
                            "accelY"     to data.accelY,
                            "accelZ"     to data.accelZ,
                            "gyroX"      to data.gyroX,
                            "gyroY"      to data.gyroY,
                            "gyroZ"      to data.gyroZ,
                            "heartRate"  to data.heartRate,
                            "latitude"   to data.latitude,
                            "longitude"  to data.longitude,
                            "imuRateHz"  to data.imuRateHz,
                            "sendRateHz" to data.sendRateHz
    )

    /** Stop both channels. Safe to call even if not running. */
    fun stop() {
        latestJob?.cancel();  latestJob  = null
        historyJob?.cancel(); historyJob = null
    }
}