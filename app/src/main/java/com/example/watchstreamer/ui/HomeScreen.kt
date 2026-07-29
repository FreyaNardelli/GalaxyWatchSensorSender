// HomeScreen.kt
package com.example.watchstreamer.ui

import com.example.watchstreamer.sensor.SensorService
import com.example.watchstreamer.sensor.StreamStatus

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel(), onMeasureDistance: () -> Unit = {}) {
    val context = LocalContext.current
    val scrollState = rememberTransformingLazyColumnState()
    var ipAddress by remember { mutableStateOf("10.142.47.206") }

    // Accel + gyro always share imuRateHz; send rate (buffer/UDP/graph cadence) is
    // independent. Both floor out at SensorService.MIN_RATE_HZ (30 Hz).
    var imuRateHz by remember { mutableStateOf(SensorService.getPersistedImuRateHz(context)) }
    var sendRateHz by remember { mutableStateOf(SensorService.getPersistedSendRateHz(context)) }

    // GPS update rate (Hz) — independent of the IMU/send rates above.
    var gpsRateHz by remember { mutableStateOf(SensorService.getPersistedGpsRateHz(context)) }

    fun cycleRate(current: Int): Int {
        val options = SensorService.AVAILABLE_RATES_HZ
        val nextIndex = (options.indexOf(current).let { if (it == -1) 0 else it } + 1) % options.size
        return options[nextIndex]
    }

    fun cycleGpsRate(current: Int): Int {
        val options = SensorService.AVAILABLE_GPS_RATES_HZ
        val nextIndex = (options.indexOf(current).let { if (it == -1) 0 else it } + 1) % options.size
        return options[nextIndex]
    }

    val streamStatus by SensorService.streamStatus.collectAsState()
    val pendingCount by SensorService.pendingCount.collectAsState()
    val location by SensorService.location.collectAsState()
    val locationSettingEnabled by SensorService.locationSettingEnabled.collectAsState()
    val sensorsPaused by SensorService.sensorsPaused.collectAsState()
    val isStreaming = streamStatus != StreamStatus.IDLE

    val transformationSpec = rememberTransformationSpec()

    fun startStream() { if (ipAddress.isNotBlank()) SensorService.startStreaming(context, ipAddress) }
    fun stopStream() { SensorService.stopStreaming(context) }

    ScreenScaffold(
        scrollState = scrollState,
        edgeButton = {
            EdgeButton(
                onClick = { if (!sensorsPaused) { if (isStreaming) stopStream() else startStream() } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isStreaming) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = if (sensorsPaused) "PAUSED" else if (isStreaming) "STOP" else "START")
            }
        }
    ) {
        TransformingLazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(
                        text = "UDP Streamer",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Text(
                    text = "Target IP:",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .transformedHeight(this, transformationSpec)
                )
            }

            item {
                BasicTextField(
                    value = ipAddress,
                    onValueChange = { if (!isStreaming) ipAddress = it }, // was silently writing to viewModel only, never updating the displayed value
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .transformedHeight(this, transformationSpec),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(text = "SAMPLING RATES", color = MaterialTheme.colorScheme.primary)
            }
            item {
                RateSelector(
                    label = "IMU (accel + gyro)",
                    valueHz = imuRateHz,
                    onTap = {
                        val next = cycleRate(imuRateHz)
                        imuRateHz = next
                        SensorService.updateRates(context, next, sendRateHz)
                    }
                )
            }
            item {
                RateSelector(
                    label = "Send (buffer + UDP)",
                    valueHz = sendRateHz,
                    onTap = {
                        val next = cycleRate(sendRateHz)
                        sendRateHz = next
                        SensorService.updateRates(context, imuRateHz, next)
                    }
                )
            }

            item {
                Button(
                    onClick = {
                        if (sensorsPaused) SensorService.resumeSensors(context) else SensorService.pauseSensors(context)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = if (sensorsPaused) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                ) {
                    Text(text = if (sensorsPaused) "Resume Sensors" else "Pause Sensors")
                }
            }

            item {
                Button(
                    onClick = { if (isStreaming) stopStream() else startStream() },
                    enabled = !sensorsPaused,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = if (isStreaming) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else ButtonDefaults.buttonColors()
                ) {
                    Text(text = if (isStreaming) "Stop" else "Start")
                }
            }

            item {
                val statusText = when {
                    sensorsPaused -> "Sensors paused — battery saving"
                    streamStatus == StreamStatus.LIVE -> "Streaming..."
                    streamStatus == StreamStatus.BUFFERING -> "Buffering • $pendingCount queued"
                    else -> null
                }
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            item { StatusHeader(streamStatus, pendingCount) }

            item { SectionHeader(text = "ACCELEROMETER", color = MaterialTheme.colorScheme.primary) }
            item { SensorGraphCard(viewModel.accelX, "X-Axis", Color(0xFF00FF88)) }
            item { SensorGraphCard(viewModel.accelY, "Y-Axis", Color(0xFF00D1FF)) }
            item { SensorGraphCard(viewModel.accelZ, "Z-Axis", Color(0xFFFF0066)) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(text = "GYROSCOPE", color = MaterialTheme.colorScheme.secondary)
            }
            item { SensorGraphCard(viewModel.gyroX, "X-Axis", Color(0xFF00FF88), maxRange = 5f) }
            item { SensorGraphCard(viewModel.gyroY, "Y-Axis", Color(0xFF00D1FF), maxRange = 5f) }
            item { SensorGraphCard(viewModel.gyroZ, "Z-Axis", Color(0xFFFF0066), maxRange = 5f) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(text = "HEART RATE", color = MaterialTheme.colorScheme.tertiary)
            }
            item { SensorGraphCard(viewModel.heartRate, "BPM", Color(0xFFFF4444), maxRange = 200f) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(text = "GPS", color = MaterialTheme.colorScheme.primary)
            }
            item {
                RateSelector(
                    label = "GPS Update Rate",
                    valueHz = gpsRateHz,
                    onTap = {
                        val next = cycleGpsRate(gpsRateHz)
                        gpsRateHz = next
                        SensorService.updateGpsRate(context, next)
                    }
                )
            }
            item {
                Text(
                    text = when {
                        sensorsPaused -> "Paused"
                        location != null -> "%.5f, %.5f".format(location!!.latitude, location!!.longitude)
                        else -> "Waiting for fix…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .transformedHeight(this, transformationSpec)
                )
            }

            if (!sensorsPaused && !locationSettingEnabled) {
                item {
                    Text(
                        text = "Location is turned off on this watch — a fix can never arrive until it's enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
                item {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Text(text = "Open Location Settings")
                    }
                }
            }

            if (!sensorsPaused) {
                item {
                    Button(
                        onClick = { SensorService.forceLocationRefresh(context) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(text = "Force GPS Reload")
                    }
                }
            }

            item {
                Button(
                    onClick = onMeasureDistance,
                    enabled = !sensorsPaused,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(text = "Measure Distance")
                }
            }

            item { Spacer(modifier = Modifier.height(48.dp)) }
        }
    }
}

@Composable
fun StatusHeader(status: StreamStatus, pendingCount: Long) {
    val infiniteTransition = rememberInfiniteTransition(label = "wifi_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    val (icon, tint, label) = when (status) {
        StreamStatus.LIVE -> Triple(Icons.Rounded.Wifi, MaterialTheme.colorScheme.primary, "STREAMING")
        StreamStatus.BUFFERING -> Triple(Icons.Rounded.Wifi, MaterialTheme.colorScheme.error, "BUFFERING ($pendingCount) \nCheck Wi-Fi connection")
        StreamStatus.IDLE -> Triple(Icons.Rounded.WifiOff, MaterialTheme.colorScheme.outline, "READY")
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = "Status",
            tint = tint,
            modifier = Modifier.size(32.dp).graphicsLayer { alpha = if (status != StreamStatus.IDLE) pulseAlpha else 1f }
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
fun SensorGraphCard(valuesFlow: StateFlow<List<Float>>, label: String, color: Color, maxRange: Float = 20f) {
    val values by valuesFlow.collectAsState()
    Box(
        modifier = Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)
    ) {
        Column {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SensorGraph(values = values, modifier = Modifier.fillMaxWidth().height(50.dp), color = color, maxRange = maxRange)
        }
    }
}

/**
 * Tap-to-cycle chip for a single rate setting, displayed as "$valueHz Hz".
 * Used by the IMU, send, and GPS rate pickers — each cycles through its own
 * list of Hz options on tap.
 */
@Composable
fun RateSelector(label: String, valueHz: Int, onTap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "$valueHz Hz",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun SensorGraph(values: List<Float>, modifier: Modifier = Modifier, color: Color = Color.Cyan, maxRange: Float = 20f) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val width = size.width
        val height = size.height
        val stepX = width / (values.size - 1)
        drawLine(color.copy(alpha = 0.2f), Offset(0f, height / 2), Offset(width, height / 2), 1.dp.toPx())
        for (i in 0 until values.size - 1) {
            val startX = i * stepX
            val startY = height / 2 - (values[i].coerceIn(-maxRange, maxRange) / maxRange) * (height / 2)
            val endX = (i + 1) * stepX
            val endY = height / 2 - (values[i + 1].coerceIn(-maxRange, maxRange) / maxRange) * (height / 2)
            drawLine(color, Offset(startX, startY), Offset(endX, endY), 2.dp.toPx())
        }
    }
}