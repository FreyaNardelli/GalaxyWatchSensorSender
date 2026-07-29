// LocationUtils.kt
package com.example.watchstreamer.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A single GPS fix, decoupled from Android's android.location.Location for easy testing. */
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Great-circle distance between two points in meters, using the Haversine formula.
 * Accurate enough for walking-distance measurements over short/medium ranges.
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusM * c
}

fun haversineMeters(a: LocationPoint, b: LocationPoint): Double =
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
