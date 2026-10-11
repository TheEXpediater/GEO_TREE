package com.geotree.app.feature.leafassessment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.data.remote.AiStatusDto
import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.AiResult
import com.geotree.app.data.repository.LeafAssessmentRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether leaf assessment can run right now. The rest of the app never depends on this. */
sealed interface AiAvailability {
    data object Checking : AiAvailability
    data class Ready(val modelDisplayName: String, val modelVersion: Int?, val classes: List<String>) : AiAvailability
    data class NotReady(val message: String) : AiAvailability
    data class Unavailable(val message: String) : AiAvailability
}

fun AiResult<AiStatusDto>.toAvailability(): AiAvailability = when (this) {
    is AiResult.Ok ->
        if (value.ready) AiAvailability.Ready(value.modelDisplayName, value.modelVersion, value.classes)
        else AiAvailability.NotReady("No trained tamarind leaf model yet. An administrator must add images and train the classifier.")
    is AiResult.Failed -> when (problem) {
        is AiProblem.ModelNotReady -> AiAvailability.NotReady(problem.message)
        else -> AiAvailability.Unavailable(problem.message)
    }
}

sealed interface LeafStage {
    data object Capture : LeafStage
    data class Review(val imagePath: String) : LeafStage
    /** Saving the new assessment record before handing over to its result page. */
    data class Starting(val imagePath: String) : LeafStage
}

data class LeafAssessmentUiState(
    val stage: LeafStage = LeafStage.Capture,
    val availability: AiAvailability = AiAvailability.Checking,
    val cameraOpen: Boolean = false,
    val message: String? = null,
    /** Set once the scan is recorded; the screen navigates to its result page. */
    val startedAssessmentId: String? = null,
)

/**
 * Capture and review only. "Assess" records a new scan in Room (one scan = one history record)
 * and hands its id to the result page; the AI request itself runs in the repository.
 */
class LeafAssessmentViewModel(
    private val treeId: String,
    private val repository: LeafAssessmentRepository,
    /** Copies a picked content:// image into app storage; null if it cannot be read. */
    private val importPicked: suspend (String) -> File?,
    private val deleteUnsaved: (String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(LeafAssessmentUiState())
    val state: StateFlow<LeafAssessmentUiState> = _state.asStateFlow()

    init {
        refreshAvailability()
    }

    fun refreshAvailability() {
        _state.update { it.copy(availability = AiAvailability.Checking) }
        viewModelScope.launch { _state.update { it.copy(availability = repository.aiStatus().toAvailability()) } }
    }

    fun openCamera() = _state.update { it.copy(cameraOpen = true, message = null) }

    fun closeCamera() = _state.update { it.copy(cameraOpen = false) }

    fun onCameraPermissionDenied() = _state.update { it.copy(message = "Camera permission is needed to photograph a leaf. You can pick a photo instead.") }

    fun onImageCaptured(file: File) {
        discardUnsubmitted()
        _state.update { it.copy(cameraOpen = false, stage = LeafStage.Review(file.absolutePath), message = null) }
    }

    fun onImagePicked(uri: String) {
        viewModelScope.launch {
            val file = importPicked(uri)
            if (file == null) {
                _state.update { it.copy(message = "That image could not be read or is larger than 10 MB.") }
            } else {
                discardUnsubmitted()
                _state.update { it.copy(stage = LeafStage.Review(file.absolutePath), message = null) }
            }
        }
    }

    /** A photo that was never submitted is not kept; a submitted photo belongs to its assessment. */
    private fun discardUnsubmitted() {
        (_state.value.stage as? LeafStage.Review)?.let { deleteUnsaved(it.imagePath) }
    }

    fun retake() {
        discardUnsubmitted()
        _state.update { it.copy(stage = LeafStage.Capture, message = null) }
    }

    fun assess() {
        val review = _state.value.stage as? LeafStage.Review ?: return
        _state.update { it.copy(stage = LeafStage.Starting(review.imagePath), message = null) }
        viewModelScope.launch {
            val record = repository.start(treeId, review.imagePath)
            // The photo now belongs to the recorded assessment and is never discarded here.
            _state.update { it.copy(stage = LeafStage.Capture, startedAssessmentId = record.id) }
        }
    }

    fun navigationHandled() = _state.update { it.copy(startedAssessmentId = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        discardUnsubmitted()
    }
}
