package com.geotree.app.feature.leafassessment

import com.geotree.app.core.database.AssessmentStatus
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.design.PillTone
import java.util.Locale

/** Pure display rules for leaf assessments, shared by Tree Detail and the assessment pages. */

fun confidenceText(confidence: Float): String = String.format(Locale.US, "%.0f%%", confidence * 100)

/** The headline for a scan: the class, or what happened instead. Never invents a class. */
fun conditionText(record: LeafAssessmentEntity): String = when (record.status) {
    AssessmentStatus.COMPLETED -> record.className ?: "Unknown"
    AssessmentStatus.UNCERTAIN -> "Uncertain · closest: ${record.className ?: "Unknown"}"
    AssessmentStatus.FAILED -> "Assessment unavailable"
    AssessmentStatus.PENDING, AssessmentStatus.PROCESSING -> "Processing…"
}

/** "DINOv2 ViT-S/14 · v2", or a plain statement when the scan never reached the model. */
fun modelText(record: LeafAssessmentEntity): String {
    val name = record.modelDisplayName ?: record.modelName ?: return "Not processed by the model"
    return listOfNotNull(name, record.modelVersion?.let { "v$it" }).joinToString(" · ")
}

fun statusLabel(status: AssessmentStatus): Pair<String, PillTone> = when (status) {
    AssessmentStatus.COMPLETED -> "Completed" to PillTone.Good
    AssessmentStatus.UNCERTAIN -> "Uncertain" to PillTone.Warning
    AssessmentStatus.FAILED -> "Failed" to PillTone.Error
    AssessmentStatus.PENDING, AssessmentStatus.PROCESSING -> "Processing" to PillTone.Amber
}

/** Network state, deliberately separate from the AI result: "AI completed, sync pending" is common. */
fun syncLabel(record: LeafAssessmentEntity): String = when {
    !record.status.isFinal -> "Not synced (in progress)"
    record.syncStatus == SyncStatus.SYNCED -> "Synced"
    record.syncStatus == SyncStatus.SYNCING -> "Syncing…"
    record.syncStatus == SyncStatus.FAILED -> "Sync pending (last attempt failed)"
    else -> "Sync pending"
}

/** One history row: "Spotting · 94%", "Uncertain · Healthy 48%", "Failed", "Processing…". */
fun historyTitle(record: LeafAssessmentEntity): String = when (record.status) {
    AssessmentStatus.COMPLETED -> listOfNotNull(record.className ?: "Unknown", record.confidence?.let(::confidenceText)).joinToString(" · ")
    AssessmentStatus.UNCERTAIN -> "Uncertain · ${record.className ?: "Unknown"}" + (record.confidence?.let { " ${confidenceText(it)}" } ?: "")
    AssessmentStatus.FAILED -> "Not assessed"
    AssessmentStatus.PENDING, AssessmentStatus.PROCESSING -> "Processing…"
}

fun assessmentCountText(count: Int): String = if (count == 1) "1 assessment" else "$count assessments"

fun availabilityPill(availability: AiAvailability): Pair<String, PillTone> = when (availability) {
    AiAvailability.Checking -> "Checking AI…" to PillTone.Neutral
    is AiAvailability.Ready -> "AI ready" to PillTone.Good
    is AiAvailability.NotReady -> "Model not ready" to PillTone.Warning
    is AiAvailability.Unavailable -> "AI server offline" to PillTone.Neutral
}
