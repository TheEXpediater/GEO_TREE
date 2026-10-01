package com.geotree.app.feature.locator.map

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geotree.app.core.location.GpsFix
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The basemap the user asked for. Tree records, markers, GPS and field guidance never depend on it.
 *
 * - OFFLINE_PSAU (default): the offline field map for [OfflineMapRegion], bundled in the APK.
 * - ONLINE_DEV: OpenStreetMap raster tiles over the internet. Development only; tiles already
 *   viewed may be cached, but this is NOT an offline map.
 */
enum class MapSourceType { OFFLINE_PSAU, ONLINE_DEV }

/** What is actually drawn under the GEO Tree layers. */
enum class ActiveBasemap { OFFLINE_PACKAGE, ONLINE_DEV, BACKGROUND_ONLY }

enum class OfflinePackageFormat { MBTILES, PMTILES }

/** Raster tile encodings MapLibre can draw with a plain raster layer. */
enum class TileEncoding { PNG, JPEG, WEBP, VECTOR, UNKNOWN;
    val isRaster: Boolean get() = this == PNG || this == JPEG || this == WEBP
}

/**
 * A local package. [coverage] is null when the package does not declare its bounds.
 * [label] is shown to the user (e.g. "PSAU / Magalang · 2026.10.01-r1").
 */
data class OfflineMapPackage(
    val file: File,
    val format: OfflinePackageFormat,
    val encoding: TileEncoding,
    val coverage: CoverageBounds? = null,
    val label: String = file.name,
)

data class Basemap(
    val requested: MapSourceType,
    val active: ActiveBasemap,
    val styleJson: String,
    val status: String,
    val packageName: String? = null,
    val coverage: CoverageBounds? = null,
) {
    /** True only when real local map tiles are drawn. */
    val hasOfflineTiles: Boolean get() = active == ActiveBasemap.OFFLINE_PACKAGE
}

/**
 * Pure selection logic. An unavailable offline package never silently turns into online tiles
 * (that would use mobile data without asking); the map then shows a plain background, and
 * markers, GPS and guidance keep working on top of it.
 */
fun resolveBasemap(requested: MapSourceType, offlinePackage: OfflineMapPackage?, offlineUnavailableReason: String? = null): Basemap {
    if (requested == MapSourceType.ONLINE_DEV) {
        return Basemap(
            requested = requested,
            active = ActiveBasemap.ONLINE_DEV,
            styleJson = MapStyles.onlineDev(),
            status = "Online map (OpenStreetMap). Needs internet; not an offline map.",
        )
    }
    val background = Basemap(requested, ActiveBasemap.BACKGROUND_ONLY, MapStyles.backgroundOnly(), status = "")
    return when {
        offlinePackage == null -> background.copy(
            status = offlineUnavailableReason ?: "Offline map is not installed. Trees, GPS and guidance still work; choose Online Map in Settings to see streets.",
        )
        !offlinePackage.encoding.isRaster -> background.copy(
            status = "${offlinePackage.file.name} is not a raster package (${offlinePackage.encoding}).",
        )
        else -> Basemap(
            requested = requested,
            active = ActiveBasemap.OFFLINE_PACKAGE,
            styleJson = MapStyles.offlinePackage(offlinePackage),
            status = "Offline map: ${offlinePackage.label}",
            packageName = offlinePackage.file.name,
            coverage = offlinePackage.coverage,
        )
    }
}

/** Whether the current position is on the offline map. Only meaningful when offline tiles are drawn. */
enum class MapCoverage { NOT_APPLICABLE, NO_FIX, INSIDE, OUTSIDE }

fun mapCoverage(basemap: Basemap, fix: GpsFix?): MapCoverage {
    val bounds = basemap.coverage
    return when {
        !basemap.hasOfflineTiles || bounds == null -> MapCoverage.NOT_APPLICABLE
        fix == null -> MapCoverage.NO_FIX
        bounds.contains(fix.latitude, fix.longitude) -> MapCoverage.INSIDE
        else -> MapCoverage.OUTSIDE
    }
}

object MapStyles {
    /** Same tone the offline renderer uses outside its coverage, so the edge reads as one surface. */
    private const val BACKGROUND = """{ "id": "background", "type": "background", "paint": { "background-color": "#E2DCCE" } }"""
    private const val ATTRIBUTION = "© OpenStreetMap contributors"

    fun onlineDev(): String = """
        {
          "version": 8,
          "sources": {
            "basemap": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "$ATTRIBUTION"
            }
          },
          "layers": [ $BACKGROUND, { "id": "basemap", "type": "raster", "source": "basemap" } ]
        }
    """.trimIndent()

    fun backgroundOnly(): String = """{ "version": 8, "sources": {}, "layers": [ $BACKGROUND ] }"""

    /**
     * Local raster package through MapLibre's built-in mbtiles:// / pmtiles:// file sources.
     * Tiles are 512 px; tileSize 256 draws them at 2x density for sharp text on phones.
     */
    fun offlinePackage(pkg: OfflineMapPackage): String {
        val path = pkg.file.absolutePath.replace('\\', '/')
        val url = when (pkg.format) {
            OfflinePackageFormat.MBTILES -> "mbtiles://$path"
            OfflinePackageFormat.PMTILES -> "pmtiles://file://$path"
        }
        return """
            {
              "version": 8,
              "sources": {
                "basemap": { "type": "raster", "url": "${jsonEscape(url)}", "tileSize": 256, "attribution": "$ATTRIBUTION" }
              },
              "layers": [ $BACKGROUND, { "id": "basemap", "type": "raster", "source": "basemap" } ]
            }
        """.trimIndent()
    }

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** Remembers which basemap the user asked for. Default: the offline field map. */
class MapSourceSettings(private val dataStore: DataStore<Preferences>) {
    val requested: Flow<MapSourceType> = dataStore.data.map { prefs -> parse(prefs[KEY]) }

    suspend fun setRequested(type: MapSourceType) {
        dataStore.edit { it[KEY] = type.name }
    }

    companion object {
        private val KEY = stringPreferencesKey("map_source")

        /** Unknown values and Give 2's "OFFLINE_PACKAGE" both mean the offline field map. */
        fun parse(stored: String?): MapSourceType = when (stored) {
            MapSourceType.ONLINE_DEV.name -> MapSourceType.ONLINE_DEV
            else -> MapSourceType.OFFLINE_PSAU
        }
    }
}

/** The bundled package once it has been installed to app storage. */
fun OfflineMapState.toPackage(): OfflineMapPackage? = (this as? OfflineMapState.Ready)?.let { ready ->
    OfflineMapPackage(
        file = ready.file,
        format = OfflinePackageFormat.MBTILES,
        encoding = when (ready.info.tileEncoding.lowercase()) {
            "png" -> TileEncoding.PNG
            "jpg", "jpeg" -> TileEncoding.JPEG
            "webp" -> TileEncoding.WEBP
            else -> TileEncoding.UNKNOWN
        },
        coverage = ready.info.bounds,
        label = "${ready.info.name} · ${ready.info.version}",
    )
}

fun OfflineMapState.unavailableReason(): String? = when (this) {
    OfflineMapState.Checking -> "Preparing offline map…"
    is OfflineMapState.Missing -> reason
    is OfflineMapState.Failed -> reason
    is OfflineMapState.Ready -> null
}

/**
 * Developer override: a package side-loaded into `<external app files>/maps/` (with `adb push`)
 * replaces the bundled one, e.g. to field-test a regenerated map without rebuilding the APK.
 * Nothing is downloaded.
 */
class OfflineMapLocator(private val searchDirs: List<File>) {
    suspend fun find(): OfflineMapPackage? = withContext(Dispatchers.IO) {
        searchDirs.asSequence()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().sortedBy { it.name }.asSequence() }
            .mapNotNull(::inspect)
            .firstOrNull()
    }

    private fun inspect(file: File): OfflineMapPackage? = when (file.extension.lowercase()) {
        "mbtiles" -> readMbtiles(file)
        "pmtiles" -> OfflineMapPackage(file, OfflinePackageFormat.PMTILES, pmtilesEncoding(file), label = "${file.name} (side-loaded)")
        else -> null
    }

    /** MBTiles metadata: "format" (png, jpg, webp, pbf) and optional "bounds" (w,s,e,n). */
    private fun readMbtiles(file: File): OfflineMapPackage {
        var encoding = TileEncoding.UNKNOWN
        var bounds: CoverageBounds? = null
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name, value FROM metadata WHERE name IN ('format', 'bounds')", null).use { c ->
                    encoding = TileEncoding.PNG // MBTiles default
                    while (c.moveToNext()) {
                        when (c.getString(0)) {
                            "format" -> encoding = encodingFromName(c.getString(1))
                            "bounds" -> bounds = parseBounds(c.getString(1))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            encoding = TileEncoding.UNKNOWN
        }
        return OfflineMapPackage(file, OfflinePackageFormat.MBTILES, encoding, bounds, label = "${file.name} (side-loaded)")
    }

    /** PMTiles v3 header: magic "PMTiles", version byte 7 = 3, tile type at byte 99. */
    private fun pmtilesEncoding(file: File): TileEncoding = try {
        val header = ByteArray(127)
        val read = file.inputStream().use { it.read(header) }
        if (read < 127 || String(header, 0, 7, Charsets.US_ASCII) != "PMTiles" || header[7].toInt() != 3) TileEncoding.UNKNOWN
        else when (header[99].toInt()) {
            1 -> TileEncoding.VECTOR
            2 -> TileEncoding.PNG
            3 -> TileEncoding.JPEG
            4 -> TileEncoding.WEBP
            else -> TileEncoding.UNKNOWN
        }
    } catch (e: Exception) {
        TileEncoding.UNKNOWN
    }

    companion object {
        fun encodingFromName(name: String?): TileEncoding = when (name?.lowercase()) {
            "png" -> TileEncoding.PNG
            "jpg", "jpeg" -> TileEncoding.JPEG
            "webp" -> TileEncoding.WEBP
            "pbf", "mvt" -> TileEncoding.VECTOR
            else -> TileEncoding.UNKNOWN
        }

        /** "west,south,east,north" → bounds, or null when malformed. */
        fun parseBounds(value: String?): CoverageBounds? {
            val parts = value?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            if (parts.size != 4) return null
            val (w, s, e, n) = parts
            return if (s < n && w < e) CoverageBounds(s, n, w, e) else null
        }
    }
}
