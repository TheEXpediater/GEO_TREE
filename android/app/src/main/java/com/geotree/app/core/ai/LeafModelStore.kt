package com.geotree.app.core.ai

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Why no usable on-device model is available. Never a reason to invent a result. */
class LeafModelUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class HeadSource { BUNDLED, DOWNLOADED }

/** A verified model ready for inference: backbone file on disk plus a compatible head. */
data class PreparedLeafModel(
    val manifest: LeafModelManifest,
    val backboneFile: File,
    val head: ClassifierHeadSpec,
    val headSha256: String,
    val headSource: HeadSource,
)

sealed interface HeadInstallResult {
    data class Installed(val modelVersion: Int) : HeadInstallResult
    data object AlreadyActive : HeadInstallResult
    data class Rejected(val reason: String) : HeadInstallResult
}

/**
 * The on-device leaf model files.
 *
 * The APK carries the complete model in `assets/leaf_model/` ([ASSET_DIR]), so the first
 * assessment works offline on a fresh install. The ONNX backbone is copied once into
 * [directory] (ONNX Runtime needs a file) and verified against the manifest's SHA-256; a corrupt
 * or truncated copy is replaced from the APK, and a corrupt APK asset is an explicit error.
 *
 * Classifier-head updates downloaded from the server are validated (format, compatibility key,
 * shape, finiteness) before they are written, written atomically, and activated by an atomic
 * pointer swap. The previous head file is kept, and a missing or damaged downloaded head falls
 * back to the bundled one, so a failed update never leaves the device without a working model.
 */
class LeafModelStore(
    private val openAsset: (String) -> InputStream,
    private val directory: File,
) {
    private val backboneDir = File(directory, "backbone")
    private val headDir = File(directory, "heads")
    private val pointer = File(headDir, "active.json")
    private val lock = Any()

    @Volatile private var verifiedBackbone: File? = null
    @Volatile private var cachedManifest: LeafModelManifest? = null

    fun manifest(): LeafModelManifest = cachedManifest ?: synchronized(lock) {
        cachedManifest ?: run {
            val manifest = try {
                parseManifest(readAssetText("manifest.json"))
            } catch (e: Exception) {
                throw LeafModelUnavailable("The leaf model is missing from this app build.", e)
            }
            manifest.problem()?.let { throw LeafModelUnavailable("The bundled leaf model cannot be used: $it") }
            manifest.also { cachedManifest = it }
        }
    }

    /** Installs/verifies the backbone and picks the active head. Blocking file I/O. */
    fun prepare(): PreparedLeafModel = synchronized(lock) {
        val manifest = manifest()
        val backbone = verifiedBackbone?.takeIf { it.isFile && it.length() == manifest.backbone.bytes } ?: installBackbone(manifest)
        val (head, sha, source) = downloadedHead(manifest) ?: bundledHead(manifest)
        PreparedLeafModel(manifest, backbone, head, sha, source)
    }

    /** Validates [bytes] as a head for this backbone and activates it. The active head is untouched on rejection. */
    fun installHead(bytes: ByteArray): HeadInstallResult = synchronized(lock) {
        val manifest = manifest()
        val spec = try {
            parseHead(bytes.decodeToString())
        } catch (e: Exception) {
            return HeadInstallResult.Rejected("Unreadable classifier: ${e.message}")
        }
        spec.problemWith(manifest)?.let { return HeadInstallResult.Rejected(it) }
        val sha = sha256(bytes)
        val current = readPointer()
        if (current?.sha256 == sha && headFile(sha).isFile) return HeadInstallResult.AlreadyActive
        headDir.mkdirs()
        writeAtomically(headFile(sha), bytes)
        if (sha256(headFile(sha).readBytes()) != sha) {
            headFile(sha).delete()
            return HeadInstallResult.Rejected("The classifier could not be stored intact.")
        }
        writeAtomically(pointer, modelJson.encodeToString(HeadPointer(sha, spec.modelVersion, current?.sha256)).encodeToByteArray())
        // Keep the new head and the previous (last known good) one; remove anything older.
        val keep = setOfNotNull(sha, current?.sha256)
        headDir.listFiles { file -> file.name.startsWith("head-") }?.forEach { file ->
            if (file.name.removePrefix("head-").removeSuffix(".json") !in keep) file.delete()
        }
        HeadInstallResult.Installed(spec.modelVersion)
    }

    /** Drops downloaded heads so the bundled head is used again (e.g. after switching servers). */
    fun resetToBundled() = synchronized(lock) {
        pointer.delete()
        headDir.listFiles { file -> file.name.startsWith("head-") }?.forEach { it.delete() }
    }

    // Backbone ------------------------------------------------------------------------------

    private fun installBackbone(manifest: LeafModelManifest): File {
        val expected = manifest.backbone.sha256.lowercase()
        val target = File(backboneDir, "$expected.onnx")
        if (target.isFile && target.length() == manifest.backbone.bytes && sha256(target) == expected) {
            return target.also { verifiedBackbone = it; removeOtherBackbones(target) }
        }
        backboneDir.mkdirs()
        val temporary = File(backboneDir, "$expected.onnx.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            openAsset("$ASSET_DIR/${manifest.backbone.file}").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            temporary.delete()
            throw LeafModelUnavailable("The leaf model could not be installed on this device (${e.message}). Check free storage.", e)
        }
        if (digest.digest().toHex() != expected || temporary.length() != manifest.backbone.bytes) {
            temporary.delete()
            throw LeafModelUnavailable("The leaf model inside this app is damaged. Reinstall GEO Tree.")
        }
        if (!temporary.renameTo(target)) {
            target.delete()
            if (!temporary.renameTo(target)) throw LeafModelUnavailable("The leaf model could not be installed on this device.")
        }
        removeOtherBackbones(target)
        return target.also { verifiedBackbone = it }
    }

    private fun removeOtherBackbones(keep: File) {
        backboneDir.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    // Heads ---------------------------------------------------------------------------------

    private fun bundledHead(manifest: LeafModelManifest): Triple<ClassifierHeadSpec, String, HeadSource> {
        val bytes = try {
            openAsset("$ASSET_DIR/${manifest.head.file}").use { it.readBytes() }
        } catch (e: IOException) {
            throw LeafModelUnavailable("The leaf classifier is missing from this app build.", e)
        }
        val sha = sha256(bytes)
        if (sha != manifest.head.sha256.lowercase()) throw LeafModelUnavailable("The leaf classifier inside this app is damaged. Reinstall GEO Tree.")
        val spec = runCatching { parseHead(bytes.decodeToString()) }.getOrElse {
            throw LeafModelUnavailable("The leaf classifier inside this app is unreadable.", it)
        }
        spec.problemWith(manifest)?.let { throw LeafModelUnavailable("The bundled leaf classifier cannot be used: $it") }
        return Triple(spec, sha, HeadSource.BUNDLED)
    }

    /** The active downloaded head, or null (none, damaged or incompatible → bundled is used). */
    private fun downloadedHead(manifest: LeafModelManifest): Triple<ClassifierHeadSpec, String, HeadSource>? {
        val active = readPointer() ?: return null
        for (sha in listOfNotNull(active.sha256, active.previousSha256)) {
            val file = headFile(sha).takeIf { it.isFile } ?: continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (sha256(bytes) != sha) continue
            val spec = runCatching { parseHead(bytes.decodeToString()) }.getOrNull() ?: continue
            if (spec.problemWith(manifest) == null) return Triple(spec, sha, HeadSource.DOWNLOADED)
        }
        return null
    }

    private fun readPointer(): HeadPointer? =
        runCatching { modelJson.decodeFromString<HeadPointer>(pointer.readText()) }.getOrNull()

    private fun headFile(sha: String) = File(headDir, "head-$sha.json")

    private fun readAssetText(name: String): String = openAsset("$ASSET_DIR/$name").use { it.readBytes().decodeToString() }

    @Serializable
    private data class HeadPointer(val sha256: String, val modelVersion: Int, val previousSha256: String? = null)

    companion object {
        const val ASSET_DIR = "leaf_model"

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) throw IOException("Could not write ${target.name}")
            }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
