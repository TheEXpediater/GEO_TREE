package com.geotree.app.core.ai

import com.geotree.app.data.remote.GeoTreeAiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ModelUpdateOutcome {
    data object UpToDate : ModelUpdateOutcome
    data class Updated(val modelVersion: Int) : ModelUpdateOutcome
    /** The server has no compatible update to offer (no model, AI disabled, older server). */
    data class Skipped(val reason: String) : ModelUpdateOutcome
    data class Rejected(val reason: String) : ModelUpdateOutcome
}

/**
 * Brings the on-device classifier head in line with the server's active model when online.
 * Best effort: any failure keeps the current head, and prediction never waits for this.
 * Only the small head is downloaded; the ~87 MB backbone ships in the APK, and a head trained
 * for a different backbone/preprocessing is rejected by [LeafModelStore.installHead].
 */
class LeafModelUpdater(
    private val store: LeafModelStore,
    private val api: suspend () -> GeoTreeAiApi,
) {
    suspend fun refresh(): ModelUpdateOutcome = withContext(Dispatchers.IO) {
        val local = try {
            store.prepare().info()
        } catch (e: LeafModelUnavailable) {
            null
        }
        val status = try {
            api().aiStatus()
        } catch (e: Exception) {
            return@withContext ModelUpdateOutcome.Skipped("AI status unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
        val serverVersion = status.modelVersion
        if (!status.ready || serverVersion == null) return@withContext ModelUpdateOutcome.Skipped("No active model on the server.")
        if (local != null && local.modelVersion == serverVersion && local.classes.sorted() == status.classes.sorted()) {
            return@withContext ModelUpdateOutcome.UpToDate
        }
        val bytes = try {
            api().modelHead().bytes()
        } catch (e: Exception) {
            // e.g. HTTP 404 from a server without the head endpoint: keep the current model.
            return@withContext ModelUpdateOutcome.Skipped("Classifier download unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
        when (val result = store.installHead(bytes)) {
            is HeadInstallResult.Installed -> ModelUpdateOutcome.Updated(result.modelVersion)
            HeadInstallResult.AlreadyActive -> ModelUpdateOutcome.UpToDate
            is HeadInstallResult.Rejected -> ModelUpdateOutcome.Rejected(result.reason)
        }
    }
}
