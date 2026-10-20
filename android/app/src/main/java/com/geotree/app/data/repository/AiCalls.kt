package com.geotree.app.data.repository

import com.geotree.app.data.remote.toApiError
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Why an AI/admin request failed, in terms the screens can act on. */
sealed interface AiProblem {
    val code: String
    val message: String

    data class Unreachable(val detail: String) : AiProblem {
        override val code = "UNREACHABLE"
        override val message = "The GEO Tree server is not reachable. Classifier administration needs a connection to the GEO Tree backend; leaf assessment works offline."
    }

    data object SignInRequired : AiProblem {
        override val code = "UNAUTHORIZED"
        override val message = "Your session has expired. Sign in again to manage classifiers."
    }

    data object AdminRequired : AiProblem {
        override val code = "ADMIN_REQUIRED"
        override val message = "Administrator access is required to manage classifiers."
    }

    data object AiDisabled : AiProblem {
        override val code = "AI_DISABLED"
        override val message = "AI is not enabled on this server, so classifiers cannot be managed here. Leaf assessment still runs on this phone."
    }

    data class ModelNotReady(override val message: String) : AiProblem {
        override val code = "AI_MODEL_NOT_READY"
    }

    /** Any other structured server error, e.g. DUPLICATE_IMAGE or CLASS_NAME_CONFLICT. */
    data class Rejected(override val code: String, override val message: String) : AiProblem
}

sealed interface AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>
    data class Failed(val problem: AiProblem) : AiResult<Nothing>
}

fun HttpException.toAiProblem(): AiProblem {
    val error = toApiError()
    return when {
        code() == 401 -> AiProblem.SignInRequired
        error.code == "ADMIN_REQUIRED" || code() == 403 -> AiProblem.AdminRequired
        error.code == "AI_DISABLED" -> AiProblem.AiDisabled
        error.code == "AI_MODEL_NOT_READY" -> AiProblem.ModelNotReady(error.message)
        else -> AiProblem.Rejected(error.code, error.message)
    }
}

suspend fun <T> aiCall(block: suspend () -> T): AiResult<T> = try {
    AiResult.Ok(block())
} catch (e: HttpException) {
    AiResult.Failed(e.toAiProblem())
} catch (e: IOException) {
    AiResult.Failed(AiProblem.Unreachable(e.message ?: e.javaClass.simpleName))
} catch (e: SerializationException) {
    AiResult.Failed(AiProblem.Rejected("BAD_RESPONSE", "The server sent an unexpected response. Is it a GEO Tree AI backend?"))
}
