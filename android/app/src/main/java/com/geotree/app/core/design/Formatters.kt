package com.geotree.app.core.design

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    private val timestamp = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss", Locale.US)

    fun latitude(value: Double): String = String.format(Locale.US, "%.6f°", value)
    fun longitude(value: Double): String = String.format(Locale.US, "%.6f°", value)
    fun coordinatePair(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    fun accuracy(meters: Float): String = String.format(Locale.US, "± %.1f m", meters)
    fun altitude(meters: Double): String = String.format(Locale.US, "%.1f m", meters)

    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timestamp.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
