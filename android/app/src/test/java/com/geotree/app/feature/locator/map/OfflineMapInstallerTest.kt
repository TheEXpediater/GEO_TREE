package com.geotree.app.feature.locator.map

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMapInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private val assets = mutableMapOf<String, ByteArray>()
    private var packageReads = 0
    private lateinit var installDir: File

    private val packageBytes = ByteArray(200_000) { (it % 251).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun info(version: String = "2026.10.01-r1", bytes: ByteArray = packageBytes, sha256: String = sha(bytes)) = OfflineMapPackageInfo(
        id = "psau_magalang", name = "PSAU / Magalang", version = version, file = "psau_magalang.mbtiles", format = "mbtiles",
        tileEncoding = "webp", bytes = bytes.size.toLong(), sha256 = sha256, minZoom = 13, maxZoom = 17,
        minLatitude = 15.198, maxLatitude = 15.24, minLongitude = 120.648, maxLongitude = 120.715,
    )

    private fun bundle(meta: OfflineMapPackageInfo, bytes: ByteArray = packageBytes) {
        assets["offline_map/metadata.json"] = Json.encodeToString(meta).toByteArray()
        assets["offline_map/${meta.file}"] = bytes
    }

    private fun installer() = OfflineMapInstaller(
        openAsset = { path ->
            if (path.endsWith(".mbtiles")) packageReads++
            assets[path]?.let { ByteArrayInputStream(it) as InputStream } ?: throw FileNotFoundException(path)
        },
        installDir = installDir,
        io = Dispatchers.Unconfined,
    )

    @Before
    fun setUp() {
        installDir = File(temp.root, "files/offline_map")
    }

    @Test
    fun `first launch extracts and verifies the package`() = runTest {
        bundle(info())
        val state = installer().ensureInstalled()

        assertTrue(state is OfflineMapState.Ready)
        val file = (state as OfflineMapState.Ready).file
        assertArrayEquals(packageBytes, file.readBytes())
        assertEquals(1, packageReads)
        assertFalse("no partial file left behind", File(installDir, "psau_magalang.mbtiles.part").exists())
    }

    @Test
    fun `later launches do not copy again`() = runTest {
        bundle(info())
        installer().ensureInstalled()
        val again = installer().ensureInstalled() // new process, same storage

        assertTrue(again is OfflineMapState.Ready)
        assertEquals("copied once only", 1, packageReads)
    }

    @Test
    fun `a new bundled version is extracted`() = runTest {
        bundle(info(version = "v1"))
        installer().ensureInstalled()
        val updated = packageBytes.copyOf().also { it[0] = 42 }
        bundle(info(version = "v2", bytes = updated), updated)

        val state = installer().ensureInstalled() as OfflineMapState.Ready

        assertEquals("v2", state.info.version)
        assertArrayEquals(updated, state.file.readBytes())
        assertEquals(2, packageReads)
    }

    @Test
    fun `a damaged installed file is replaced`() = runTest {
        bundle(info())
        val first = installer().ensureInstalled() as OfflineMapState.Ready
        first.file.writeBytes(ByteArray(10)) // truncated by storage trouble

        val state = installer().ensureInstalled() as OfflineMapState.Ready

        assertArrayEquals(packageBytes, state.file.readBytes())
        assertEquals(2, packageReads)
    }

    @Test
    fun `checksum mismatch fails without leaving a package`() = runTest {
        bundle(info(sha256 = "0".repeat(64)))
        val state = installer().ensureInstalled()

        assertTrue(state is OfflineMapState.Failed)
        assertFalse(File(installDir, "psau_magalang.mbtiles").exists())
        assertFalse(File(installDir, "psau_magalang.mbtiles.part").exists())
    }

    @Test
    fun `a build without a package reports missing`() = runTest {
        val installer = installer()
        val state = installer.ensureInstalled()
        assertTrue(state is OfflineMapState.Missing)
        assertEquals(state, installer.state.value)
    }

    @Test
    fun `extraction decision`() {
        val bundled = info(version = "v2")
        assertTrue(OfflineMapInstaller.needsExtraction(bundled, installed = null, installedFileBytes = null))
        assertTrue(OfflineMapInstaller.needsExtraction(bundled, info(version = "v1"), bundled.bytes))
        assertTrue(OfflineMapInstaller.needsExtraction(bundled, bundled, installedFileBytes = null))
        assertTrue(OfflineMapInstaller.needsExtraction(bundled, bundled, bundled.bytes - 1))
        assertTrue(OfflineMapInstaller.needsExtraction(bundled, bundled.copy(sha256 = "ff"), bundled.bytes))
        assertFalse(OfflineMapInstaller.needsExtraction(bundled, bundled, bundled.bytes))
    }
}
