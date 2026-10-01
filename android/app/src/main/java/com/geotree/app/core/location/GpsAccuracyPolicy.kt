package com.geotree.app.core.location

enum class GpsQuality { GOOD, ACCEPTABLE, LOW }

/**
 * Prototype accuracy thresholds, in meters, kept in one place so they can be tuned
 * after field trials:  <= 5 m GOOD, > 5 to <= 10 m ACCEPTABLE, > 10 m LOW.
 */
data class GpsAccuracyPolicy(
    val goodMaxMeters: Float = 5f,
    val acceptableMaxMeters: Float = 10f,
) {
    init {
        require(goodMaxMeters in 0f..acceptableMaxMeters) { "goodMaxMeters must be between 0 and acceptableMaxMeters" }
    }

    fun classify(accuracyMeters: Float): GpsQuality = when {
        accuracyMeters <= goodMaxMeters -> GpsQuality.GOOD
        accuracyMeters <= acceptableMaxMeters -> GpsQuality.ACCEPTABLE
        else -> GpsQuality.LOW
    }

    companion object {
        val Default = GpsAccuracyPolicy()
        const val LOW_ACCURACY_MESSAGE = "GPS accuracy is currently low. Move to an open area and capture again."
    }
}

/**
 * A real reading reported by Android location services. Never constructed from user input.
 * [speedMps] / [speedAccuracyMps] are null when Android did not report them; they are raw
 * readings and must go through a plausibility filter before being shown as the user's speed.
 */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val capturedAt: Long,
    val quality: GpsQuality,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
)
