// MeasureDistanceScreen.kt
package com.example.watchstreamer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.watchstreamer.sensor.LocationPoint
import com.example.watchstreamer.sensor.SensorService
import com.example.watchstreamer.sensor.haversineMeters
import kotlin.math.roundToInt

private enum class WalkState { IDLE, WALKING, FINISHED }

@Composable
fun MeasureDistanceScreen(onBack: () -> Unit = {}) {
    val scrollState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    val location by SensorService.location.collectAsState()

    var walkState by remember { mutableStateOf(WalkState.IDLE) }
    var startPoint by remember { mutableStateOf<LocationPoint?>(null) }
    var finalDistanceMeters by remember { mutableStateOf(0.0) }

    val liveDistanceMeters = if (walkState == WalkState.WALKING && startPoint != null && location != null) {
        haversineMeters(startPoint!!, location!!)
    } else null

    ScreenScaffold(
        scrollState = scrollState,
        edgeButton = {
            EdgeButton(onClick = onBack) {
                Text(text = "Back")
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
                        text = "Measure Distance",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Text(
                    text = "Current position:",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .transformedHeight(this, transformationSpec)
                )
            }

            item {
                Text(
                    text = location?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "Waiting for GPS fix…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .transformedHeight(this, transformationSpec)
                )
            }

            when (walkState) {
                WalkState.IDLE -> {
                    item {
                        Button(
                            onClick = {
                                location?.let {
                                    startPoint = it
                                    walkState = WalkState.WALKING
                                }
                            },
                            enabled = location != null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(text = "Start Walk")
                        }
                    }
                }

                WalkState.WALKING -> {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Distance so far",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(liveDistanceMeters ?: 0.0).roundToInt()} m",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                finalDistanceMeters = liveDistanceMeters ?: 0.0
                                walkState = WalkState.FINISHED
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(text = "End Walk")
                        }
                    }
                }

                WalkState.FINISHED -> {
                    item {
                        Text(
                            text = "${finalDistanceMeters.roundToInt()} meters walked",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    item {
                        Button(
                            onClick = {
                                startPoint = null
                                finalDistanceMeters = 0.0
                                walkState = WalkState.IDLE
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(text = "Measure Again")
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(48.dp)) }
        }
    }
}
