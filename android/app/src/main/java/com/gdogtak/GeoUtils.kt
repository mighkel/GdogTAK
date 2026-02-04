package com.gdogtak

import kotlin.math.*

/**
 * Geographic utility functions for bearing and distance calculations.
 */
object GeoUtils {

    /**
     * Calculate distance between two points using the Haversine formula.
     * @return distance in meters
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Calculate bearing from point 1 to point 2.
     * @return bearing in degrees (0-360, 0=North, 90=East)
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * Format distance for display.
     * Imperial: feet (<1000 ft) or miles. Metric: meters (<1000 m) or km.
     */
    fun formatDistance(meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * 3.28084
            return if (feet < 1000) {
                "${feet.toInt()} ft"
            } else {
                val miles = meters / 1609.344
                "%.1f mi".format(miles)
            }
        } else {
            return if (meters < 1000) {
                "${meters.toInt()} m"
            } else {
                "%.1f km".format(meters / 1000.0)
            }
        }
    }

    /**
     * Format bearing as cardinal direction.
     */
    fun formatBearing(degrees: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                           "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((degrees + 11.25) / 22.5).toInt() % 16
        return dirs[index]
    }

    /**
     * Format bearing as cardinal + degrees, e.g. "NE (045)"
     */
    fun formatBearingFull(degrees: Double): String {
        return "${formatBearing(degrees)} (%03d)".format(degrees.toInt())
    }
}
