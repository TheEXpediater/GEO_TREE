package com.geotree.app.data.repository

/** Why an on-device leaf assessment produced no result, in terms the screens can show. */
sealed interface AiProblem {
    val code: String
    val message: String

    /** The bundled model is missing, damaged or incompatible: no prediction is made. */
    data class ModelNotReady(override val message: String) : AiProblem {
        override val code = "AI_MODEL_NOT_READY"
    }

    /** The photo could not be assessed, e.g. IMAGE_MISSING, UNSUPPORTED_IMAGE or INFERENCE_FAILED. */
    data class Rejected(override val code: String, override val message: String) : AiProblem
}

sealed interface AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>
    data class Failed(val problem: AiProblem) : AiResult<Nothing>
}
