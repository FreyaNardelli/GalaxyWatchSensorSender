// SensorService.kt
package com.example.watchstreamer.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.watchstreamer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

enum class StreamStatus { IDLE, LIVE, BUFFERING }

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var heartRateSensor: Sensor? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null
    private var senderJob: Job? = null

    private var currentAccel = FloatArray(3)
    private var currentGyro = FloatArray(3)
    private var currentHeartRate = 0f

    private lateinit var bufferStore: SensorBufferStore
    private lateinit var prefs: android.content.SharedPreferences

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var targetIp: String? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        bufferStore = SensorBufferStore.get(applicationContext)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        createNotificationChannel()
        val notification = createNotification(StreamStatus.IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        startSampling()

        // START_STICKY redelivers a null Intent after a system-triggered restart, so
        // recover "was streaming" from prefs rather than relying on intent extras.
        if (prefs.getBoolean(KEY_STREAMING, false)) {
            prefs.getString(KEY_TARGET_IP, null)?.let { beginStreaming(it) }
        }
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "WatchStreamer:SensorWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WatchStreamer:WifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun startSampling() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch {
            while (isActive) {
                val data = SensorData(
                    timestamp = System.currentTimeMillis(),
                    accelX = currentAccel[0], accelY = currentAccel[1], accelZ = currentAccel[2],
                    gyroX = currentGyro[0], gyroY = currentGyro[1], gyroZ = currentGyro[2],
                    heartRate = currentHeartRate
                )
                _sensorFlow.emit(data)
                // Only buffer while the user has actually pressed Start — this is what
                // makes the data durable against any interruption (screen-dark, radio
                // sleep, wifi hiccup) between here and the receiving computer.
                if (targetIp != null) {
                    bufferStore.enqueue(data)
                }
                delay(100) // 10Hz
            }
        }
    }

    private fun beginStreaming(ip: String) {
        targetIp = ip
        prefs.edit().putBoolean(KEY_STREAMING, true).putString(KEY_TARGET_IP, ip).apply()
        acquireLocks()
        _streamStatus.value = StreamStatus.BUFFERING
        updateNotification(StreamStatus.BUFFERING)

        senderJob?.cancel()
        senderJob = serviceScope.launch(Dispatchers.IO) {
            val socket = DatagramSocket()
            val address = try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                _streamStatus.value = StreamStatus.IDLE
                socket.close()
                return@launch
            }

            try {
                while (isActive) {
                    val batch = bufferStore.peekBatch(BATCH_SIZE)
                    if (batch.isEmpty()) {
                        _pendingCount.value = 0
                        if (_streamStatus.value != StreamStatus.LIVE) {
                            _streamStatus.value = StreamStatus.LIVE
                            updateNotification(StreamStatus.LIVE)
                        }
                        delay(50)
                        continue
                    }

                    var lastSentId: Long? = null
                    var failed = false
                    for ((id, data) in batch) {
                        try {
                            val payload = data.toListPayloadString().toByteArray()
                            socket.send(DatagramPacket(payload, payload.size, address, UDP_PORT))
                            lastSentId = id
                        } catch (e: IOException) {
                            failed = true
                            break
                        }
                    }

                    lastSentId?.let { bufferStore.removeUpTo(it) }
                    _pendingCount.value = bufferStore.pendingCount()

                    if (failed) {
                        // Connection's down — back off, keep buffering, retry the probe.
                        if (_streamStatus.value != StreamStatus.BUFFERING) {
                            _streamStatus.value = StreamStatus.BUFFERING
                            updateNotification(StreamStatus.BUFFERING)
                        }
                        delay(RETRY_DELAY_MS)
                    }
                    // No artificial delay on success: when there's a backlog we drain it
                    // as fast as the network allows. Once caught up, the empty-batch
                    // branch above naturally settles into a 50ms poll.
                }
            } finally {
                socket.close()
            }
        }
    }

    private fun stopStreaming() {
        targetIp = null
        prefs.edit().putBoolean(KEY_STREAMING, false).apply()
        senderJob?.cancel()
        senderJob = null
        releaseLocks()
        _streamStatus.value = StreamStatus.IDLE
        updateNotification(StreamStatus.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let { beginStreaming(it) }
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccel[0] = event.values[0]; currentAccel[1] = event.values[1]; currentAccel[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyro[0] = event.values[0]; currentGyro[1] = event.values[1]; currentGyro[2] = event.values[2]
            }
            Sensor.TYPE_HEART_RATE -> if (event.values.isNotEmpty()) currentHeartRate = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        samplingJob?.cancel()
        senderJob?.cancel()
        sensorManager.unregisterListener(this)
        releaseLocks()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sensor Streaming", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(status: StreamStatus): Notification {
        val text = when (status) {
            StreamStatus.LIVE -> "Streaming live at 10 Hz"
            StreamStatus.BUFFERING -> "Buffering locally — reconnecting..."
            StreamStatus.IDLE -> "Reading sensors"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Watch Streamer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: StreamStatus) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(status))
    }

    companion object {
        private const val CHANNEL_ID = "sensor_channel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "watch_streamer_prefs"
        private const val KEY_STREAMING = "is_streaming"
        private const val KEY_TARGET_IP = "target_ip"
        private const val UDP_PORT = 12345
        private const val BATCH_SIZE = 50
        private const val RETRY_DELAY_MS = 1000L

        const val ACTION_START = "com.example.watchstreamer.action.START_STREAM"
        const val ACTION_STOP = "com.example.watchstreamer.action.STOP_STREAM"
        const val EXTRA_IP = "extra_ip"

        private val _sensorFlow = MutableSharedFlow<SensorData>(replay = 1)
        val sensorFlow = _sensorFlow.asSharedFlow()

        private val _streamStatus = MutableStateFlow(StreamStatus.IDLE)
        val streamStatus = _streamStatus.asStateFlow()

        private val _pendingCount = MutableStateFlow(0L)
        val pendingCount = _pendingCount.asStateFlow()

        fun startStreaming(context: Context, ip: String) {
            val intent = Intent(context, SensorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IP, ip)
            }
            context.startForegroundService(intent)
        }

        fun stopStreaming(context: Context) {
            context.startService(Intent(context, SensorService::class.java).apply { action = ACTION_STOP })
        }
    }
}