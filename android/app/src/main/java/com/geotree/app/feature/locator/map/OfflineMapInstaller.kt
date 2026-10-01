package com.geotree.app.feature.locator.map

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** offline_map/metadata.json as written by tools/offline_map/build_offline_map.py. */
@Serializable
data class OfflineMapPackageInfo(
    val id: String,
    val name: String,
    val version: String,
    val file: String,
    val format: String,
    @SerialName("tile_encoding") val tileEncoding: String = "png",
    val bytes: Long,
    val sha256: String,
    @SerialName("min_zoom") val minZoom: Int,
    @SerialName("max_zoom") val maxZoom: Int,
    @SerialName("min_latitude") val minLatitude: Double,
    @SerialName("max_latitude") val maxLatitude: Double,
    @SerialName("min_longitude") val minLongitude: Double,
    @SerialName("max_longitude") val maxLongitude: Double,
    val attribution: String = "© OpenStreetMap contributors",
    @SerialName("osm_data_timestamp") val osmDataTimestamp: String? = null,
) {
    val bounds: CoverageBounds get() = CoverageBounds(minLatitude, maxLatitude, minLongitude, maxLongitude)
}

sealed interface OfflineMapState {
    data object Checking : OfflineMapState
    data class Ready(val info: OfflineMapPackageInfo, val file: File) : OfflineMapState
    /** This build has no bundled package. */
    data class Missing(val reason: String) : OfflineMapState
    data class Failed(val reason: String) : OfflineMapState
}

/**
 * Extracts the bundled offline map from APK assets to app-private storage once.
 *
 * MapLibre's MBTiles source needs a real SQLite file, so the package cannot be read from inside
 * the APK. The copy is streamed, size- and SHA-256-verified, then moved into place; a marker file
 * records the installed version so later launches only compare metadata and file size.
 */
class OfflineMapInstaller(
    private val openAsset: (String) -> InputStream,
    private val installDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<OfflineMapState>(OfflineMapState.Checking)
    val state: StateFlow<OfflineMapState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val markerFile get() = File(installDir, MARKER)

    suspend fun ensureInstalled(): OfflineMapState = mutex.withLock {
        withContext(io) { install() }.also { _state.value = it }
    }

    private fun install(): OfflineMapState {
        val bundled = try {
            openAsset(METADATA_ASSET).use { json.decodeFromString<OfflineMapPackageInfo>(it.readBytes().decodeToString()) }
        } catch (e: FileNotFoundException) {
            return OfflineMapState.Missing("This build has no offline map package.")
        } catch (e: Exception) {
            return OfflineMapState.Failed("Offline map metadata is unreadable: ${e.message}")
        }
        val target = File(installDir, bundled.file)
        val installed = readMarker()
        if (!needsExtraction(bundled, installed, target.takeIf { it.isFile }?.length())) {
            return OfflineMapState.Ready(bundled, target)
        }
        return extract(bundled, target)
    }

    private fun extract(info: OfflineMapPackageInfo, target: File): OfflineMapState {
        installDir.mkdirs()
        val part = File(installDir, "${info.file}.part")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            openAsset("$ASSET_DIR/${info.file}").use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (copied != info.bytes || !sha.equals(info.sha256, ignoreCase = true)) {
                part.delete()
                return OfflineMapState.Failed("Offline map package failed verification (size or checksum mismatch).")
            }
            markerFile.delete()
            if (target.exists() && !target.delete()) return OfflineMapState.Failed("Could not replace the old offline map package.")
            if (!part.renameTo(target)) return OfflineMapState.Failed("Could not move the offline map package into place.")
            markerFile.writeText(json.encodeToString(info))
            OfflineMapState.Ready(info, target)
        } catch (e: FileNotFoundException) {
            part.delete()
            OfflineMapState.Missing("The offline map file is missing from this build.")
        } catch (e: Exception) {
            part.delete()
            OfflineMapState.Failed("Could not prepare the offline map: ${e.message}")
        }
    }

    private fun readMarker(): OfflineMapPackageInfo? = try {
        markerFile.takeIf { it.isFile }?.readText()?.let { json.decodeFromString<OfflineMapPackageInfo>(it) }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ASSET_DIR = "offline_map"
        const val METADATA_ASSET = "$ASSET_DIR/metadata.json"
        private const val MARKER = "installed.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Copy only when nothing valid is installed or the bundled package changed. */
        fun needsExtraction(bundled: OfflineMapPackageInfo, installed: OfflineMapPackageInfo?, installedFileBytes: Long?): Boolean =
            installed == null ||
                installed.version != bundled.version ||
                !installed.sha256.equals(bundled.sha256, ignoreCase = true) ||
                installed.file != bundled.file ||
                installedFileBytes != bundled.bytes
    }
}
