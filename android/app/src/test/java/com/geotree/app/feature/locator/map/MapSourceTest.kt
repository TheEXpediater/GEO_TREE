package com.geotree.app.feature.locator.map

import com.geotree.app.testing.gpsFix
import com.geotree.app.testing.testDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = storeScope.cancel()

    private val psau = OfflineMapPackage(
        File("/data/user/0/com.geotree.app/files/offline_map/psau_magalang.mbtiles"),
        OfflinePackageFormat.MBTILES,
        TileEncoding.WEBP,
        coverage = OfflineMapRegion.bounds,
        label = "PSAU / Magalang · test",
    )

    @Test
    fun `offline PSAU map draws local tiles with no network source`() {
        val basemap = resolveBasemap(MapSourceType.OFFLINE_PSAU, psau)
        assertEquals(ActiveBasemap.OFFLINE_PACKAGE, basemap.active)
        assertTrue(basemap.hasOfflineTiles)
        assertEquals(OfflineMapRegion.bounds, basemap.coverage)
        // On Android absolutePath is "/data/..."; the JVM test host may add a drive letter.
        val path = psau.file.absolutePath.replace('\\', '/')
        assertTrue(basemap.styleJson.contains("\"mbtiles://$path\""))
        assertFalse("no online tiles in the offline style", basemap.styleJson.contains("http"))
        assertTrue(basemap.styleJson.contains("OpenStreetMap contributors"))
    }

    @Test
    fun `missing offline package never falls back to online tiles`() {
        val basemap = resolveBasemap(MapSourceType.OFFLINE_PSAU, null, "Preparing offline map…")
        assertEquals(ActiveBasemap.BACKGROUND_ONLY, basemap.active)
        assertFalse(basemap.hasOfflineTiles)
        assertFalse("must not silently use mobile data", basemap.styleJson.contains("http"))
        assertEquals("Preparing offline map…", basemap.status)
    }

    @Test
    fun `online map is opt-in and labelled as not offline`() {
        val basemap = resolveBasemap(MapSourceType.ONLINE_DEV, psau)
        assertEquals(ActiveBasemap.ONLINE_DEV, basemap.active)
        assertFalse(basemap.hasOfflineTiles)
        assertTrue(basemap.status.contains("not an offline map"))
        assertTrue(basemap.styleJson.contains("tile.openstreetmap.org"))
    }

    @Test
    fun `vector packages are not drawn as raster`() {
        val vector = psau.copy(encoding = TileEncoding.VECTOR)
        assertEquals(ActiveBasemap.BACKGROUND_ONLY, resolveBasemap(MapSourceType.OFFLINE_PSAU, vector).active)
    }

    @Test
    fun `pmtiles uses the pmtiles file url`() {
        val pm = OfflineMapPackage(File("/data/maps/area.pmtiles"), OfflinePackageFormat.PMTILES, TileEncoding.WEBP)
        val pmPath = pm.file.absolutePath.replace('\\', '/')
        assertTrue(resolveBasemap(MapSourceType.OFFLINE_PSAU, pm).styleJson.contains("pmtiles://file://$pmPath"))
    }

    @Test
    fun `coverage state reports inside, outside and no fix only for offline tiles`() {
        val offline = resolveBasemap(MapSourceType.OFFLINE_PSAU, psau)
        assertEquals(MapCoverage.NO_FIX, mapCoverage(offline, null))
        assertEquals(MapCoverage.INSIDE, mapCoverage(offline, gpsFix(15.21851, 120.69557)))
        assertEquals(MapCoverage.INSIDE, mapCoverage(offline, gpsFix(15.2167, 120.6600))) // Magalang poblacion
        assertEquals(MapCoverage.OUTSIDE, mapCoverage(offline, gpsFix(15.1449, 120.5887))) // Angeles-side, outside
        assertEquals(MapCoverage.NOT_APPLICABLE, mapCoverage(resolveBasemap(MapSourceType.ONLINE_DEV, psau), gpsFix(15.1449, 120.5887)))
        assertEquals(MapCoverage.NOT_APPLICABLE, mapCoverage(resolveBasemap(MapSourceType.OFFLINE_PSAU, null), gpsFix(15.1449, 120.5887)))
    }

    @Test
    fun `map source setting defaults to the offline map and maps the legacy value`() = runTest {
        assertEquals(MapSourceType.OFFLINE_PSAU, MapSourceSettings.parse(null))
        assertEquals(MapSourceType.OFFLINE_PSAU, MapSourceSettings.parse("OFFLINE_PACKAGE")) // Give 2 value
        assertEquals(MapSourceType.OFFLINE_PSAU, MapSourceSettings.parse("garbage"))
        assertEquals(MapSourceType.ONLINE_DEV, MapSourceSettings.parse("ONLINE_DEV"))

        val settings = MapSourceSettings(testDataStore(temp.newFolder(), storeScope))
        assertEquals(MapSourceType.OFFLINE_PSAU, settings.requested.first())
        settings.setRequested(MapSourceType.ONLINE_DEV)
        assertEquals(MapSourceType.ONLINE_DEV, settings.requested.first())
        settings.setRequested(MapSourceType.OFFLINE_PSAU)
        assertEquals(MapSourceType.OFFLINE_PSAU, settings.requested.first())
    }

    @Test
    fun `mbtiles bounds metadata parses west south east north`() {
        assertEquals(CoverageBounds(15.198, 15.24, 120.648, 120.715), OfflineMapLocator.parseBounds("120.648,15.198,120.715,15.24"))
        assertNull(OfflineMapLocator.parseBounds("1,2,3"))
        assertNull(OfflineMapLocator.parseBounds("120.7,15.3,120.6,15.2")) // inverted
        assertNull(OfflineMapLocator.parseBounds(null))
    }

    @Test
    fun `installed bundled package becomes a raster webp package with region coverage`() {
        val info = OfflineMapPackageInfo(
            id = "psau_magalang", name = "PSAU / Magalang", version = "v1", file = "psau_magalang.mbtiles", format = "mbtiles",
            tileEncoding = "webp", bytes = 10, sha256 = "00", minZoom = 13, maxZoom = 17,
            minLatitude = 15.198, maxLatitude = 15.24, minLongitude = 120.648, maxLongitude = 120.715,
        )
        val pkg = OfflineMapState.Ready(info, File("x.mbtiles")).toPackage()!!
        assertEquals(TileEncoding.WEBP, pkg.encoding)
        assertEquals(OfflineMapRegion.bounds, pkg.coverage)
        assertNull(OfflineMapState.Checking.toPackage())
        assertEquals("Preparing offline map…", OfflineMapState.Checking.unavailableReason())
        assertNull(OfflineMapState.Ready(info, File("x")).unavailableReason())
    }
}
