// MainActivity.kt

package com.example.watchstreamer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.*
import com.example.watchstreamer.sensor.SensorService
import com.example.watchstreamer.ui.HomeScreen
import com.example.watchstreamer.ui.MeasureDistanceScreen
import com.example.watchstreamer.ui.theme.WatchStreamerTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute : NavKey

@Serializable
object MeasureDistanceRoute : NavKey

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchStreamerTheme {
                val permissions = remember {
                    val list = mutableListOf(
                        Manifest.permission.BODY_SENSORS,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    list
                }

                val permissionState = rememberMultiplePermissionsState(permissions)

                if (permissionState.allPermissionsGranted) {
                    LaunchedEffect(Unit) {
                        val intent = Intent(this@MainActivity, SensorService::class.java)
                        startForegroundService(intent)
                    }
                    WatchApp()
                } else {
                    PermissionScreen(
                        onRequestPermission = {
                            permissionState.launchMultiplePermissionRequest()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Sensor access needed for streaming data",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestPermission) {
                Text(text = "Grant Access")
            }
        }
    }
}

@Composable
fun WatchApp() {
    val backStack = rememberNavBackStack(HomeRoute)

    AppScaffold {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) },
            entryProvider = entryProvider {
                entry<HomeRoute> {
                    HomeScreen(
                        onMeasureDistance = { backStack.add(MeasureDistanceRoute) }
                    )
                }
                entry<MeasureDistanceRoute> {
                    MeasureDistanceScreen(
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) }
                    )
                }
            }
        )
    }
}
