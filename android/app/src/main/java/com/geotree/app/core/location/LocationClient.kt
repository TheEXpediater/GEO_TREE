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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * How often a continuous stream asks for fixes. Only one stream runs at a time on the map:
 * [LOCATOR] for the current-location cue, [NAVIGATION] while field guidance is active.
 */
enum class LocationUpdateProfile(val intervalMillis: Long, val minIntervalMillis: Long) {
    LOCATOR(intervalMillis = 4_000, minIntervalMillis = 2_000),
    NAVIGATION(intervalMillis = 2_500, minIntervalMillis = 1_000),
}

/** What screens need from location services; lets ViewModels be tested without Play Services. */
interface LocationSource {
    /** The newest real fix seen by this process (any stream or a tag capture), or null. */
    val lastFix: StateFlow<GpsFix?>
    fun permission(): LocationPermission
    fun isLocationEnabled(): Boolean
    /** Continuous high-accuracy updates. Updates stop when the collector is cancelled. */
    fun updates(profile: LocationUpdateProfile = LocationUpdateProfile.LOCATOR): Flow<GpsFix>
}

/**
 * Wraps the Fused Location Provider. All location work happens here, never in a Composable.
 */
class LocationClient(
    private val context: Context,
    private val policy: GpsAccuracyPolicy = GpsAccuracyPolicy.Default,
) : LocationSource {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _lastFix = MutableStateFlow<GpsFix?>(null)
    override val lastFix: StateFlow<GpsFix?> = _lastFix.asStateFlow()

    override fun permission(): LocationPermission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NONE
    }

    override fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    /**
     * Android's cached last location, for status displays only (e.g. the Dashboard's
     * "last known accuracy"). Never used for tagging, which always requests a fresh fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun refreshLastKnown(): GpsFix? {
        if (permission() == LocationPermission.NONE) return _lastFix.value
        val cached = try {
            fused.lastLocation.await()?.takeIf { it.hasAccuracy() }?.toFix()
        } catch (e: Exception) {
            null
        }
        if (cached != null) publish(cached)
        return _lastFix.value
    }

    private fun publish(fix: GpsFix) {
        val current = _lastFix.value
        if (current == null || fix.capturedAt >= current.capturedAt) _lastFix.value = fix
    }

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
                else -> FixResult.Success(location.toFix().also(::publish))
            }
        } catch (e: SecurityException) {
            FixResult.PermissionDenied
        } catch (e: Exception) {
            FixResult.Failure(e.message ?: "Location services returned an error.")
        } finally {
            cancellation.cancel()
        }
    }

    /**
     * Continuous updates (FusedLocationProviderClient.requestLocationUpdates). The request is
     * removed in awaitClose, so cancelling the collector always releases GPS.
     */
    @SuppressLint("MissingPermission")
    override fun updates(profile: LocationUpdateProfile): Flow<GpsFix> = callbackFlow {
        if (permission() == LocationPermission.NONE) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, profile.intervalMillis)
            .setMinUpdateIntervalMillis(profile.minIntervalMillis)
            .setMaxUpdateAgeMillis(0) // never show a stale cached fix as the current position
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.takeIf { it.hasAccuracy() }?.let { location ->
                    val fix = location.toFix()
                    publish(fix)
                    trySend(fix)
                }
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
        speedMps = if (hasSpeed()) speed else null,
        speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
