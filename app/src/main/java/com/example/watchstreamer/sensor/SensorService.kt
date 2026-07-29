// SensorService.kt

package com.example.watchstreamer.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.watchstreamer.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

    @Volatile private var currentLat = 0.0
    @Volatile private var currentLon = 0.0

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locationCancellationSource: CancellationTokenSource? = null

    private lateinit var bufferStore: SensorBufferStore
    private lateinit var prefs: android.content.SharedPreferences

    // Sensor callbacks are dispatched on this dedicated background thread instead of
    // the main thread. Compose recomposition + a flood of onSensorChanged() calls
    // sharing the main thread is what was causing the watchdog to kill the app a
    // few seconds after launch at high rates.
    private val sensorHandlerThread = HandlerThread("SensorListenerThread").apply { start() }
    private val sensorHandler = Handler(sensorHandlerThread.looper)

    // Current live rates (Hz). Accel + gyro always share imuRateHz.
    @Volatile private var imuRateHz: Int = DEFAULT_RATE_HZ
    @Volatile private var sendRateHz: Int = DEFAULT_RATE_HZ

    // Current live GPS update rate (Hz). Independent of the IMU/send rates
    // above; converted to a millisecond period only when building the
    // LocationRequest, since that API takes an interval rather than a rate.
    @Volatile private var gpsRateHz: Int = DEFAULT_GPS_RATE_HZ

    // ── Cloud channel ──────────────────────────────────────────────────────────
    // Runs alongside the UDP sender. Pushes the latest reading to Firebase at
    // 2 Hz so the iPhone can receive data over the internet when UDP is not
    // reachable (different network, WiFi hiccup, etc.).
    private lateinit var firebaseUploader: FirebaseUploader

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialise the cloud uploader (does not connect to Firebase yet —
        // that happens inside FirebaseUploader.start() when streaming begins).
        firebaseUploader = FirebaseUploader(serviceScope)

        createNotificationChannel()
        val notification = createNotification(StreamStatus.IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        imuRateHz = prefs.getInt(KEY_IMU_RATE_HZ, DEFAULT_RATE_HZ).coerceAtLeast(MIN_RATE_HZ)
        sendRateHz = prefs.getInt(KEY_SEND_RATE_HZ, DEFAULT_RATE_HZ).coerceAtLeast(MIN_RATE_HZ)
        gpsRateHz = prefs.getInt(KEY_GPS_RATE_HZ, DEFAULT_GPS_RATE_HZ)
            .coerceAtLeast(AVAILABLE_GPS_RATES_HZ.first())

        // Respect a paused session across process/service restarts — if the user
        // paused sensors before the watch rebooted or Android killed the service,
        // don't silently start burning battery again on their behalf.
        _sensorsPaused.value = prefs.getBoolean(KEY_SENSORS_PAUSED, false)
        if (_sensorsPaused.value) {
            updateNotification(StreamStatus.IDLE)
        } else {
            checkLocationSettingEnabled()
            startLocationUpdates()
            registerSensors()
            startSampling()

            // START_STICKY redelivers a null Intent after a system-triggered restart, so
            // recover "was streaming" from prefs rather than relying on intent extras.
            if (prefs.getBoolean(KEY_STREAMING, false)) {
                prefs.getString(KEY_TARGET_IP, null)?.let { beginStreaming(it) }
            }
        }
    }

    /**
     * Registers accel + gyro at an *explicit* period derived from imuRateHz, on the
     * background sensorHandler thread — never SENSOR_DELAY_FASTEST, and never on the
     * main thread. FASTEST is uncapped (some watches will fire 400-800+ events/sec
     * per sensor) and callbacks without a Handler land on the calling thread, i.e.
     * the main thread that Compose is also using — that combination is what was
     * causing the app to be killed a few seconds after launch.
     */
    private fun registerSensors() {
        val periodUs = hzToPeriodUs(imuRateHz)
        accelerometer?.let { sensorManager.registerListener(this, it, periodUs, sensorHandler) }
        gyroscope?.let { sensorManager.registerListener(this, it, periodUs, sensorHandler) }
        // Heart rate is inherently a low-rate sensor on watch hardware; leave it be.
        heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler) }
    }

    private fun unregisterSensors() {
        accelerometer?.let { sensorManager.unregisterListener(this, it) }
        gyroscope?.let { sensorManager.unregisterListener(this, it) }
        heartRateSensor?.let { sensorManager.unregisterListener(this, it) }
    }

    /** Applies a new (imuHz, sendHz) pair live, persists it, and restarts the affected loops. */
    private fun applyRates(newImuHz: Int, newSendHz: Int) {
        val clampedImu = newImuHz.coerceAtLeast(MIN_RATE_HZ)
        val clampedSend = newSendHz.coerceAtLeast(MIN_RATE_HZ)

        imuRateHz = clampedImu
        sendRateHz = clampedSend
        prefs.edit().putInt(KEY_IMU_RATE_HZ, clampedImu).putInt(KEY_SEND_RATE_HZ, clampedSend).apply()

        // While paused, just persist the new rates — they'll take effect next
        // time sensors are resumed. Re-registering here would silently turn
        // the sensors back on behind the "Pause Sensors" button's back.
        if (!_sensorsPaused.value) {
            unregisterSensors()
            registerSensors()
            startSampling() // restart with the new send-loop delay
        }

        if (_streamStatus.value != StreamStatus.IDLE) updateNotification(_streamStatus.value)
    }

    /**
     * Applies a new GPS update rate live, persists it, and restarts the
     * location session so the change takes effect immediately — mirrors
     * applyRates() for the IMU/send pickers. While paused, just persist the
     * new value; it takes effect next time sensors are resumed, same as the
     * other rate pickers.
     */
    private fun applyGpsRate(newHz: Int) {
        val clamped = newHz.coerceAtLeast(AVAILABLE_GPS_RATES_HZ.first())
        gpsRateHz = clamped
        prefs.edit().putInt(KEY_GPS_RATE_HZ, clamped).apply()

        if (!_sensorsPaused.value) {
            stopLocationUpdates()
            startLocationUpdates()
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

    /**
     * Live GPS coordinates are shown on the watch screen regardless of
     * whether streaming is active, so location updates run continuously
     * once permission has been granted.
     */
    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        if (locationCallback != null) return // already running

        val intervalMs = hzToPeriodMs(gpsRateHz)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLat = loc.latitude
                currentLon = loc.longitude
                _location.value = LocationPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    timestamp = loc.time
                )
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            locationCallback = null
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    /**
     * Checks the *system-level* Location toggle (Settings > Location on the watch),
     * independent of whether our own runtime permission was granted. This is the
     * most common reason a location app never gets a fix: requestLocationUpdates()
     * does not throw or report an error when the device's Location setting is off
     * — the callback simply never fires, forever, even outdoors under a clear sky.
     * Exposed via a StateFlow so the UI can show a clear message instead of an
     * indefinite "Waiting for fix…" that looks identical to a slow GPS cold start.
     */
    private fun checkLocationSettingEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        _locationSettingEnabled.value = LocationManagerCompat.isLocationEnabled(locationManager)
    }

    /**
     * Forces a fresh GPS seek. A GNSS session that has stopped producing fixes
     * (common after the watch sleeps, after a Bluetooth/Wi-Fi handoff, or just a
     * bad cold-start lock) does not reliably recover by itself — the existing
     * requestLocationUpdates() callback can sit open but silent indefinitely.
     * Fully removing and re-adding the callback makes Play Services tear down and
     * restart the underlying location session rather than continuing to wait on
     * whatever session it already had open. We also fire an independent one-shot
     * getCurrentLocation() request, since it takes a different internal path in
     * Play Services and can return a fix even in cases where the streaming
     * callback path is the part that's stuck.
     */
    private fun forceLocationRefresh() {
        if (_sensorsPaused.value) return // GPS is intentionally off — nothing to reseek

        stopLocationUpdates()
        _location.value = null // reflect "reacquiring" immediately in the UI
        checkLocationSettingEnabled()
        startLocationUpdates()

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        locationCancellationSource?.cancel()
        val cancellationSource = CancellationTokenSource()
        locationCancellationSource = cancellationSource
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSource.token)
                .addOnSuccessListener { loc ->
                    loc ?: return@addOnSuccessListener
                    currentLat = loc.latitude
                    currentLon = loc.longitude
                    _location.value = LocationPoint(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        timestamp = loc.time
                    )
                }
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call; nothing to do.
        }
    }

    /**
     * Pauses every sensor input the app reads: IMU (accel/gyro), heart rate, and
     * GPS. This is the single biggest lever for battery life when the watch isn't
     * being worn — a continuously-registered high-rate IMU listener plus a
     * permanently-open GPS/GNSS session both keep the watch's hardware awake even
     * while nothing is being recorded. Also stops any active stream, since there's
     * no live data left to send once sensors are off.
     */
    private fun pauseSensors() {
        if (_sensorsPaused.value) return
        if (_streamStatus.value != StreamStatus.IDLE) stopStreaming()

        samplingJob?.cancel()
        samplingJob = null
        unregisterSensors()
        locationCancellationSource?.cancel()
        stopLocationUpdates()

        _sensorsPaused.value = true
        prefs.edit().putBoolean(KEY_SENSORS_PAUSED, true).apply()
        updateNotification(_streamStatus.value)
    }

    /** Resumes IMU, heart rate, and GPS sampling after a pause. */
    private fun resumeSensors() {
        if (!_sensorsPaused.value) return

        registerSensors()
        checkLocationSettingEnabled()
        startLocationUpdates()
        startSampling()

        _sensorsPaused.value = false
        prefs.edit().putBoolean(KEY_SENSORS_PAUSED, false).apply()
        updateNotification(_streamStatus.value)
    }

    private fun startSampling() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch {
            while (isActive) {
                val data = SensorData(
                    timestamp = System.currentTimeMillis(),
                    accelX = currentAccel[0], accelY = currentAccel[1], accelZ = currentAccel[2],
                    gyroX = currentGyro[0], gyroY = currentGyro[1], gyroZ = currentGyro[2],
                    heartRate = currentHeartRate,
                    latitude = currentLat,
                    longitude = currentLon,
                    imuRateHz = imuRateHz,
                    sendRateHz = sendRateHz
                )
                _sensorFlow.emit(data)

                // Only buffer and upload while the user has pressed Start.
                // This keeps Firebase / SQLite clean between sessions.
                if (targetIp != null) {
                    bufferStore.enqueue(data)

                    // ── Cloud channel: hand the latest reading to the Firebase
                    //    uploader. It samples this at its own 2 Hz cadence so
                    //    it never floods Firebase regardless of send rate.
                    firebaseUploader.onNewData(data)
                }

                delay(1000L / sendRateHz)
            }
        }
    }

    private fun beginStreaming(ip: String) {
        if (_sensorsPaused.value) return // no live sensor data to stream while paused

        targetIp = ip
        prefs.edit().putBoolean(KEY_STREAMING, true).putString(KEY_TARGET_IP, ip).apply()
        acquireLocks()
        _streamStatus.value = StreamStatus.BUFFERING
        updateNotification(StreamStatus.BUFFERING)

        // ── Start both channels ────────────────────────────────────────────────
        firebaseUploader.start()   // cloud channel (2 Hz, internet-wide reach)

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
                        // UDP is down — back off and keep buffering.
                        // Firebase continues uploading independently (it has its
                        // own retry logic built into the SDK).
                        if (_streamStatus.value != StreamStatus.BUFFERING) {
                            _streamStatus.value = StreamStatus.BUFFERING
                            updateNotification(StreamStatus.BUFFERING)
                        }
                        delay(RETRY_DELAY_MS)
                    }
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
        firebaseUploader.stop()    // stop cloud channel
        releaseLocks()
        _streamStatus.value = StreamStatus.IDLE
        updateNotification(StreamStatus.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let { beginStreaming(it) }
            ACTION_STOP -> stopStreaming()
            ACTION_UPDATE_RATES -> {
                val imuHz = intent.getIntExtra(EXTRA_IMU_RATE_HZ, imuRateHz)
                val sendHz = intent.getIntExtra(EXTRA_SEND_RATE_HZ, sendRateHz)
                applyRates(imuHz, sendHz)
            }
            ACTION_UPDATE_GPS_RATE -> {
                val hz = intent.getIntExtra(EXTRA_GPS_RATE_HZ, gpsRateHz)
                applyGpsRate(hz)
            }
            ACTION_FORCE_LOCATION_REFRESH -> forceLocationRefresh()
            ACTION_PAUSE_SENSORS -> pauseSensors()
            ACTION_RESUME_SENSORS -> resumeSensors()
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
        firebaseUploader.stop()
        sensorManager.unregisterListener(this)
        sensorHandlerThread.quitSafely()
        locationCancellationSource?.cancel()
        stopLocationUpdates()
        releaseLocks()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sensor Streaming", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(status: StreamStatus): Notification {
        val text = when {
            _sensorsPaused.value -> "Sensors paused — battery saving"
            status == StreamStatus.LIVE -> "Streaming live at $sendRateHz Hz (IMU $imuRateHz Hz) · cloud sync active"
            status == StreamStatus.BUFFERING -> "Buffering locally — reconnecting..."
            else -> "Reading sensors"
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
        private const val KEY_IMU_RATE_HZ = "imu_rate_hz"
        private const val KEY_SEND_RATE_HZ = "send_rate_hz"
        private const val KEY_GPS_RATE_HZ = "gps_rate_hz"
        private const val KEY_SENSORS_PAUSED = "sensors_paused"
        private const val UDP_PORT = 12345
        private const val BATCH_SIZE = 50
        private const val RETRY_DELAY_MS = 1000L

        /** Floor for both rates. Below this the home-monitoring feature quality drops too much. */
        const val MIN_RATE_HZ = 30
        const val DEFAULT_RATE_HZ = 30

        /** Discrete steps offered on the home screen rate pickers. */
        val AVAILABLE_RATES_HZ = listOf(30, 50, 75, 100)

        /** Default GPS update rate (Hz) — unchanged from the app's original fixed 1 Hz behavior. */
        const val DEFAULT_GPS_RATE_HZ = 1

        /** Discrete steps offered on the home screen GPS rate picker (Hz). */
        val AVAILABLE_GPS_RATES_HZ = listOf(1, 2, 5, 10)

        const val ACTION_START = "com.example.watchstreamer.action.START_STREAM"
        const val ACTION_STOP = "com.example.watchstreamer.action.STOP_STREAM"
        const val ACTION_UPDATE_RATES = "com.example.watchstreamer.action.UPDATE_RATES"
        const val ACTION_UPDATE_GPS_RATE = "com.example.watchstreamer.action.UPDATE_GPS_RATE"
        const val ACTION_FORCE_LOCATION_REFRESH = "com.example.watchstreamer.action.FORCE_LOCATION_REFRESH"
        const val ACTION_PAUSE_SENSORS = "com.example.watchstreamer.action.PAUSE_SENSORS"
        const val ACTION_RESUME_SENSORS = "com.example.watchstreamer.action.RESUME_SENSORS"
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_IMU_RATE_HZ = "extra_imu_rate_hz"
        const val EXTRA_SEND_RATE_HZ = "extra_send_rate_hz"
        const val EXTRA_GPS_RATE_HZ = "extra_gps_rate_hz"

        private fun hzToPeriodUs(hz: Int): Int = (1_000_000 / hz)

        /** Converts a GPS update rate (Hz) to the millisecond interval LocationRequest.Builder expects. */
        private fun hzToPeriodMs(hz: Int): Long = (1000L / hz)

        /** Reads the currently persisted rates so the UI can initialize its state before the service is even running. */
        fun getPersistedImuRateHz(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_IMU_RATE_HZ, DEFAULT_RATE_HZ).coerceAtLeast(MIN_RATE_HZ)

        fun getPersistedSendRateHz(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SEND_RATE_HZ, DEFAULT_RATE_HZ).coerceAtLeast(MIN_RATE_HZ)

        /** Reads the currently persisted GPS rate so the UI can initialize its state before the service is even running. */
        fun getPersistedGpsRateHz(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_GPS_RATE_HZ, DEFAULT_GPS_RATE_HZ)
                .coerceAtLeast(AVAILABLE_GPS_RATES_HZ.first())

        /**
         * Persists the new rates immediately (so they stick even if the service isn't
         * running yet) and, if the service is alive, applies them live without needing
         * a restart.
         */
        fun updateRates(context: Context, imuHz: Int, sendHz: Int) {
            val clampedImu = imuHz.coerceAtLeast(MIN_RATE_HZ)
            val clampedSend = sendHz.coerceAtLeast(MIN_RATE_HZ)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_IMU_RATE_HZ, clampedImu)
                .putInt(KEY_SEND_RATE_HZ, clampedSend)
                .apply()

            val intent = Intent(context, SensorService::class.java).apply {
                action = ACTION_UPDATE_RATES
                putExtra(EXTRA_IMU_RATE_HZ, clampedImu)
                putExtra(EXTRA_SEND_RATE_HZ, clampedSend)
            }
            context.startService(intent)
        }

        /**
         * Persists the new GPS update rate immediately (so it sticks even if the
         * service isn't running yet) and, if the service is alive, applies it
         * live without needing a restart. Mirrors updateRates() above.
         */
        fun updateGpsRate(context: Context, hz: Int) {
            val clamped = hz.coerceAtLeast(AVAILABLE_GPS_RATES_HZ.first())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_GPS_RATE_HZ, clamped)
                .apply()

            val intent = Intent(context, SensorService::class.java).apply {
                action = ACTION_UPDATE_GPS_RATE
                putExtra(EXTRA_GPS_RATE_HZ, clamped)
            }
            context.startService(intent)
        }

        private val _sensorFlow = MutableSharedFlow<SensorData>(replay = 1)
        val sensorFlow = _sensorFlow.asSharedFlow()

        private val _streamStatus = MutableStateFlow(StreamStatus.IDLE)
        val streamStatus = _streamStatus.asStateFlow()

        private val _pendingCount = MutableStateFlow(0L)
        val pendingCount = _pendingCount.asStateFlow()

        // Live GPS fix — updated continuously (independent of streaming state)
        // so the watch screen can always show the current coordinates.
        private val _location = MutableStateFlow<LocationPoint?>(null)
        val location = _location.asStateFlow()

        // Whether the watch's system-level Location toggle is on. False here means
        // a fix will never arrive no matter how long you wait — it's not a slow
        // cold start, GPS is simply switched off at the OS level.
        private val _locationSettingEnabled = MutableStateFlow(true)
        val locationSettingEnabled = _locationSettingEnabled.asStateFlow()

        // Whether raw sensor input (IMU, heart rate, GPS) is currently paused.
        private val _sensorsPaused = MutableStateFlow(false)
        val sensorsPaused = _sensorsPaused.asStateFlow()

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

        /** Forces a fresh GPS seek — tears down and rebuilds the location session. */
        fun forceLocationRefresh(context: Context) {
            context.startService(Intent(context, SensorService::class.java).apply { action = ACTION_FORCE_LOCATION_REFRESH })
        }

        /** Pauses IMU, heart rate, and GPS sampling (and stops any active stream). */
        fun pauseSensors(context: Context) {
            context.startService(Intent(context, SensorService::class.java).apply { action = ACTION_PAUSE_SENSORS })
        }

        /** Resumes IMU, heart rate, and GPS sampling after a pause. */
        fun resumeSensors(context: Context) {
            context.startService(Intent(context, SensorService::class.java).apply { action = ACTION_RESUME_SENSORS })
        }
    }
}