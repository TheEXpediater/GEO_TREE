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

    private val shortDate = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.US)

    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timestamp.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun shortDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortDate.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "Just now", "5 min ago", "3 hr ago", then an absolute date. */
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = (now - epochMillis).coerceAtLeast(0) / 1_000
        return when {
            elapsed < 60 -> "Just now"
            elapsed < 3_600 -> "${elapsed / 60} min ago"
            elapsed < 86_400 -> "${elapsed / 3_600} hr ago"
            else -> shortDateTime(epochMillis, zone)
        }
    }
}
