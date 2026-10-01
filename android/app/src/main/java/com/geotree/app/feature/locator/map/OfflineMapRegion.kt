package com.geotree.app.feature.locator.map

import com.geotree.app.feature.navigation.GeoPoint

/**
 * The deployment area covered by the bundled offline field map. Single source of truth for
 * GEO Tree's geographic constants; must match tools/offline_map/region.json (a unit test checks
 * this against the bundled package metadata).
 *
 * The default centre is the "Pampanga State Agricultural University" node in OpenStreetMap.
 * The box covers the PSAU campus and fields, Magalang poblacion and the roads between them,
 * about 7.2 km × 4.6 km.
 */
object OfflineMapRegion {
    const val ID = "psau_magalang"
    const val NAME = "PSAU / Magalang"

    const val DEFAULT_CENTER_LATITUDE = 15.21851
    const val DEFAULT_CENTER_LONGITUDE = 120.69557

    const val MIN_LATITUDE = 15.1980
    const val MAX_LATITUDE = 15.2400
    const val MIN_LONGITUDE = 120.6480
    const val MAX_LONGITUDE = 120.7150

    /** Zoom levels rendered into the package. MapLibre over-zooms beyond [MAX_ZOOM]. */
    const val MIN_ZOOM = 13
    const val MAX_ZOOM = 17

    val center: GeoPoint get() = GeoPoint(DEFAULT_CENTER_LATITUDE, DEFAULT_CENTER_LONGITUDE)

    val bounds: CoverageBounds get() = CoverageBounds(MIN_LATITUDE, MAX_LATITUDE, MIN_LONGITUDE, MAX_LONGITUDE)

    fun contains(latitude: Double, longitude: Double): Boolean = bounds.contains(latitude, longitude)

    /** Human-readable problems with the constants above; empty when consistent. */
    fun validate(): List<String> = buildList {
        if (MIN_LATITUDE >= MAX_LATITUDE) add("MIN_LATITUDE must be below MAX_LATITUDE")
        if (MIN_LONGITUDE >= MAX_LONGITUDE) add("MIN_LONGITUDE must be below MAX_LONGITUDE")
        if (MIN_LATITUDE < -85.0 || MAX_LATITUDE > 85.0) add("latitudes must stay within Web Mercator limits")
        if (MIN_LONGITUDE < -180.0 || MAX_LONGITUDE > 180.0) add("longitudes must be within -180..180")
        if (!contains(DEFAULT_CENTER_LATITUDE, DEFAULT_CENTER_LONGITUDE)) add("default centre must be inside the region")
        if (MIN_ZOOM !in 0..MAX_ZOOM) add("MIN_ZOOM must be between 0 and MAX_ZOOM")
        if (MAX_ZOOM > 22) add("MAX_ZOOM must be 22 or less")
    }
}

/** A latitude/longitude box. Used for the bundled region and for side-loaded packages. */
data class CoverageBounds(val minLatitude: Double, val maxLatitude: Double, val minLongitude: Double, val maxLongitude: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude
}
