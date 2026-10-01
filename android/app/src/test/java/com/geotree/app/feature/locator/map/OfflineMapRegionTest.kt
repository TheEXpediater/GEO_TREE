package com.geotree.app.feature.locator.map

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapRegionTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Unit tests run with the module directory (android/app) as the working directory.
    private val assetDir = File("src/main/assets/offline_map")
    private val regionConfig = File("../../tools/offline_map/region.json")

    @Test
    fun `region constants are internally consistent`() {
        assertEquals(emptyList<String>(), OfflineMapRegion.validate())
    }

    @Test
    fun `points inside and outside the field area`() {
        assertTrue("PSAU", OfflineMapRegion.contains(15.21851, 120.69557))
        assertTrue("Magalang town plaza area", OfflineMapRegion.contains(15.2140, 120.6616))
        assertTrue("corner is inclusive", OfflineMapRegion.contains(OfflineMapRegion.MIN_LATITUDE, OfflineMapRegion.MIN_LONGITUDE))
        assertFalse("Angeles City", OfflineMapRegion.contains(15.1449, 120.5887))
        assertFalse("Mt. Arayat summit", OfflineMapRegion.contains(15.2006, 120.7417))
        assertFalse("just north", OfflineMapRegion.contains(OfflineMapRegion.MAX_LATITUDE + 0.0001, 120.69))
        assertFalse("swapped lat/lon", OfflineMapRegion.contains(120.69557, 15.21851))
    }

    @Test
    fun `kotlin region matches the map builder config`() {
        val r = json.parseToJsonElement(regionConfig.readText()).jsonObject
        assertEquals(OfflineMapRegion.ID, r["id"]!!.jsonPrimitive.content)
        assertEquals(OfflineMapRegion.MIN_LATITUDE, r["min_latitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.MAX_LATITUDE, r["max_latitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.MIN_LONGITUDE, r["min_longitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.MAX_LONGITUDE, r["max_longitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.DEFAULT_CENTER_LATITUDE, r["center_latitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.DEFAULT_CENTER_LONGITUDE, r["center_longitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(OfflineMapRegion.MIN_ZOOM, r["min_zoom"]!!.jsonPrimitive.int)
        assertEquals(OfflineMapRegion.MAX_ZOOM, r["max_zoom"]!!.jsonPrimitive.int)
    }

    @Test
    fun `bundled package matches its metadata and the region`() {
        val info = json.decodeFromString<OfflineMapPackageInfo>(File(assetDir, "metadata.json").readText())
        assertEquals(OfflineMapRegion.ID, info.id)
        assertEquals(OfflineMapRegion.bounds, info.bounds)
        assertEquals(OfflineMapRegion.MIN_ZOOM, info.minZoom)
        assertEquals(OfflineMapRegion.MAX_ZOOM, info.maxZoom)
        assertEquals("webp", info.tileEncoding)
        assertTrue(info.attribution.contains("OpenStreetMap"))

        val file = File(assetDir, info.file)
        assertEquals("package size", info.bytes, file.length())
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("package checksum", info.sha256, sha)
        assertTrue("keep the bundled map small (< 40 MB)", info.bytes < 40_000_000)
    }
}
