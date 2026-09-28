package com.geotree.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

enum class LocationPermission { PRECISE, APPROXIMATE, NONE }

sealed interface FixResult {
    data class Success(val fix: GpsFix) : FixResult
    data object PermissionDenied : FixResult
    data object LocationDisabled : FixResult
    data object NoFix : FixResult
    data class Failure(val message: String) : FixResult
}

/**
 * Wraps the Fused Location Provider. All location work happens here, never in a Composable.
 */
class LocationClient(
    private val context: Context,
    private val policy: GpsAccuracyPolicy = GpsAccuracyPolicy.Default,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun permission(): LocationPermission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NONE
    }

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    /**
     * Requests a fresh fix (maxUpdateAge = 0) so a stale cached location is never saved
     * for a user-initiated tag.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshFix(timeoutMillis: Long = 30_000): FixResult {
        val permission = permission()
        if (permission == LocationPermission.NONE) return FixResult.PermissionDenied
        if (!isLocationEnabled()) return FixResult.LocationDisabled

        val request = CurrentLocationRequest.Builder()
            .setPriority(
                if (permission == LocationPermission.PRECISE) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            )
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(timeoutMillis)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            val location = withTimeoutOrNull(timeoutMillis + 2_000) {
                fused.getCurrentLocation(request, cancellation.token).await()
            }
            when {
                location == null -> FixResult.NoFix
                !location.hasAccuracy() -> FixResult.Failure("Location has no accuracy estimate. Capture again.")
                else -> FixResult.Success(location.toFix())
            }
        } catch (e: SecurityException) {
            FixResult.PermissionDenied
        } catch (e: Exception) {
            FixResult.Failure(e.message ?: "Location services returned an error.")
        } finally {
            cancellation.cancel()
        }
    }

    /** Continuous updates for the locator's current-location cue. */
    @SuppressLint("MissingPermission")
    fun updates(intervalMillis: Long = 2_000): Flow<GpsFix> = callbackFlow {
        if (permission() == LocationPermission.NONE) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .setMaxUpdateAgeMillis(0) // never show a stale cached fix as the current position
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.takeIf { it.hasAccuracy() }?.let { trySend(it.toFix()) }
            }
        }
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    private fun Location.toFix(): GpsFix = GpsFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        altitudeMeters = if (hasAltitude()) altitude else null,
        capturedAt = time,
        quality = policy.classify(accuracy),
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
