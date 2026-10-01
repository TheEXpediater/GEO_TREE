package com.geotree.app.feature.dashboard

import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.PillTone
import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.network.BackendReachability
import com.geotree.app.core.network.BackendStatus
import com.geotree.app.data.repository.SyncCounts
import com.geotree.app.feature.locator.SyncIndicator
import com.geotree.app.feature.locator.map.CoverageBounds
import com.geotree.app.feature.locator.map.OfflineMapState
import java.util.Locale
import kotlin.math.cos

enum class GpsAvailability { PERMISSION_REQUIRED, LOCATION_OFF, AVAILABLE }

/** Everything the Dashboard shows. Tree numbers come from Room only. */
data class DashboardUiState(
    val counts: SyncCounts = SyncCounts(),
    val recentTrees: List<TreeEntity> = emptyList(),
    val gps: GpsAvailability = GpsAvailability.AVAILABLE,
    val lastFix: GpsFix? = null,
    val deviceOnline: Boolean? = null,
    val backend: BackendStatus = BackendStatus(BackendReachability.CHECKING),
    val backendUrl: String = "",
    val lastSyncAt: Long? = null,
    val sync: SyncIndicator = SyncIndicator("…", PillTone.Neutral),
    val syncRunning: Boolean = false,
    val offlineMap: OfflineMapState = OfflineMapState.Checking,
) {
    /** PENDING and FAILED records are both retried by the next sync. */
    val waitingToSync: Int get() = counts.pending + counts.failed
}

const val RECENT_TREE_LIMIT = 5

/** Most recently added or updated first; creation time breaks ties. */
fun recentTrees(trees: List<TreeEntity>, limit: Int = RECENT_TREE_LIMIT): List<TreeEntity> =
    trees.sortedWith(compareByDescending<TreeEntity> { it.updatedAt }.thenByDescending { it.createdAt }).take(limit)

fun gpsAvailability(permission: LocationPermission, locationEnabled: Boolean): GpsAvailability = when {
    permission == LocationPermission.NONE -> GpsAvailability.PERMISSION_REQUIRED
    !locationEnabled -> GpsAvailability.LOCATION_OFF
    else -> GpsAvailability.AVAILABLE
}

data class StatusLabel(val text: String, val tone: PillTone)

fun BackendStatus.display(): StatusLabel = when (reachability) {
    BackendReachability.CHECKING -> StatusLabel("Checking", PillTone.Neutral)
    BackendReachability.CONNECTED -> StatusLabel("Connected", PillTone.Good)
    BackendReachability.OFFLINE -> StatusLabel("Offline", PillTone.Warning)
}

fun GpsAvailability.display(): StatusLabel = when (this) {
    GpsAvailability.AVAILABLE -> StatusLabel("GPS available", PillTone.Good)
    GpsAvailability.PERMISSION_REQUIRED -> StatusLabel("GPS permission needed", PillTone.Amber)
    GpsAvailability.LOCATION_OFF -> StatusLabel("Location off", PillTone.Error)
}

fun networkDisplay(online: Boolean?): StatusLabel = when (online) {
    true -> StatusLabel("Online", PillTone.Good)
    false -> StatusLabel("Offline", PillTone.Warning)
    null -> StatusLabel("Network…", PillTone.Neutral)
}

/** "3 records waiting to sync", or null when nothing is waiting. */
fun pendingMessage(waiting: Int): String? = when {
    waiting <= 0 -> null
    waiting == 1 -> "1 record waiting to sync"
    else -> "$waiting records waiting to sync"
}

fun offlineMapStatusLabel(state: OfflineMapState): StatusLabel = when (state) {
    OfflineMapState.Checking -> StatusLabel("Preparing", PillTone.Neutral)
    is OfflineMapState.Ready -> StatusLabel("Ready", PillTone.Good)
    is OfflineMapState.Missing -> StatusLabel("Not installed", PillTone.Warning)
    is OfflineMapState.Failed -> StatusLabel("Unavailable", PillTone.Error)
}

/** "15.1980–15.2400° N · 120.6480–120.7150° E (≈7.2 × 4.6 km)". */
fun coverageText(bounds: CoverageBounds): String {
    val midLatitude = Math.toRadians((bounds.minLatitude + bounds.maxLatitude) / 2)
    val widthKm = (bounds.maxLongitude - bounds.minLongitude) * 111.32 * cos(midLatitude)
    val heightKm = (bounds.maxLatitude - bounds.minLatitude) * 110.57
    return String.format(
        Locale.US, "%.4f–%.4f° N · %.4f–%.4f° E (≈%.1f × %.1f km)",
        bounds.minLatitude, bounds.maxLatitude, bounds.minLongitude, bounds.maxLongitude, widthKm, heightKm,
    )
}
