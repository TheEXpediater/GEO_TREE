package com.geotree.app.data.model

import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.location.GpsQuality

/** Optional field notes. None of these may block geotagging. */
data class TreeDetails(
    val age: Int? = null,
    val tasteCategory: String? = null,
    val yearlyYield: Double? = null,
    val fruitQuality: String? = null,
    val notes: String? = null,
)

data class TreeDraft(
    val treeCode: String,
    val localImagePath: String?,
    val fix: GpsFix?,
    val details: TreeDetails = TreeDetails(),
)

enum class DraftError(val message: String) {
    TREE_CODE_BLANK("Enter a Tree Code."),
    TREE_CODE_FORMAT("Use letters, numbers and dashes, e.g. GEO-TAM-003 (max 40)."),
    LOCATION_MISSING("Acquire the tree's GPS location first."),
    LATITUDE_RANGE("Latitude must be between -90 and 90."),
    LONGITUDE_RANGE("Longitude must be between -180 and 180."),
    ACCURACY_INVALID("GPS accuracy must be zero or more."),
    AGE_INVALID("Age must be a whole number of years (0 or more)."),
    YIELD_INVALID("Yearly yield must be zero or more."),
}

data class DraftValidation(val errors: Set<DraftError>, val lowAccuracy: Boolean) {
    val isValid: Boolean get() = errors.isEmpty()
}

object TreeCode {
    private val pattern = Regex("^[A-Z0-9][A-Z0-9_-]{0,39}$")

    fun normalize(raw: String): String = raw.trim().uppercase()

    fun isValid(normalized: String): Boolean = pattern.matches(normalized)
}

object TreeDraftValidator {
    fun validate(draft: TreeDraft): DraftValidation {
        val errors = mutableSetOf<DraftError>()
        val code = TreeCode.normalize(draft.treeCode)
        when {
            code.isEmpty() -> errors += DraftError.TREE_CODE_BLANK
            !TreeCode.isValid(code) -> errors += DraftError.TREE_CODE_FORMAT
        }
        val fix = draft.fix
        if (fix == null) {
            errors += DraftError.LOCATION_MISSING
        } else {
            if (fix.latitude.isNaN() || fix.latitude !in -90.0..90.0) errors += DraftError.LATITUDE_RANGE
            if (fix.longitude.isNaN() || fix.longitude !in -180.0..180.0) errors += DraftError.LONGITUDE_RANGE
            if (fix.accuracyMeters.isNaN() || fix.accuracyMeters < 0f) errors += DraftError.ACCURACY_INVALID
        }
        draft.details.age?.let { if (it < 0) errors += DraftError.AGE_INVALID }
        draft.details.yearlyYield?.let { if (it.isNaN() || it < 0.0) errors += DraftError.YIELD_INVALID }
        return DraftValidation(errors, lowAccuracy = fix?.quality == GpsQuality.LOW)
    }
}
