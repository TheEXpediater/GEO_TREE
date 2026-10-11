package com.geotree.app.feature.classifiers

import com.geotree.app.core.design.PillTone
import com.geotree.app.data.remote.ClassifierClassDto
import java.util.Locale

data class StatusLabel(val text: String, val tone: PillTone)

/** Overall classifier status: UNTRAINED | QUEUED | TRAINING | READY | ERROR (+ stale when the dataset changed). */
fun modelStatusLabel(status: String?, stale: Boolean = false): StatusLabel = when (status) {
    "READY" -> if (stale) StatusLabel("Ready · rebuild pending", PillTone.Amber) else StatusLabel("Ready", PillTone.Good)
    "QUEUED" -> StatusLabel("Queued", PillTone.Amber)
    "TRAINING" -> StatusLabel("Training", PillTone.Amber)
    "ERROR" -> StatusLabel("Error", PillTone.Error)
    "UNTRAINED" -> StatusLabel("Untrained", PillTone.Neutral)
    null -> StatusLabel("Checking…", PillTone.Neutral)
    else -> StatusLabel(status.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }, PillTone.Neutral)
}

/** Per-class readiness for training, e.g. "Needs 3 more" for a class below the minimum. */
fun classStatusLabel(item: ClassifierClassDto, minImages: Int): StatusLabel = when (item.status) {
    "READY" -> StatusLabel("Ready", PillTone.Good)
    "PENDING_FEATURES" -> StatusLabel("Computing features", PillTone.Amber)
    else -> {
        val missing = (minImages - item.imageCount).coerceAtLeast(0)
        if (missing > 0) StatusLabel("Needs $missing more", PillTone.Warning) else StatusLabel("Insufficient", PillTone.Warning)
    }
}

fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100)

fun imageCountText(count: Int): String = if (count == 1) "1 image" else "$count images"
