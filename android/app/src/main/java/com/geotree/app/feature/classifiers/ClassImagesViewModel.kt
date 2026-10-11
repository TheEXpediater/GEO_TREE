package com.geotree.app.feature.classifiers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.data.remote.DatasetImageDto
import com.geotree.app.data.remote.TrainingJobDto
import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.AiResult
import com.geotree.app.data.repository.ClassifierRepository
import com.geotree.app.data.repository.LocalImageReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UploadProgress(val done: Int, val total: Int, val uploaded: Int, val failures: List<String>) {
    val finished: Boolean get() = done == total
}

data class ClassImagesUiState(
    val loading: Boolean = true,
    val className: String = "",
    val images: List<DatasetImageDto> = emptyList(),
    val selected: Set<String> = emptySet(),
    val upload: UploadProgress? = null,
    val deleting: Boolean = false,
    val problem: AiProblem? = null,
    val message: String? = null,
    /** Classifier status after the last change, e.g. QUEUED for the coalesced retraining job. */
    val trainingStatus: String? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val busy: Boolean get() = deleting || (upload != null && !upload.finished)
}

class ClassImagesViewModel(
    private val classId: String,
    private val repository: ClassifierRepository,
    private val reader: LocalImageReader,
) : ViewModel() {
    private val _state = MutableStateFlow(ClassImagesUiState())
    val state: StateFlow<ClassImagesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (val result = repository.images(classId)) {
                is AiResult.Ok -> _state.update { state ->
                    val ids = result.value.items.map { it.id }.toSet()
                    state.copy(loading = false, problem = null, className = result.value.className, images = result.value.items, selected = state.selected intersect ids)
                }
                is AiResult.Failed -> _state.update { it.copy(loading = false, problem = result.problem) }
            }
        }
    }

    suspend fun thumbnailUrl(imageId: String): String = repository.thumbnailUrl(classId, imageId)

    fun toggleSelection(imageId: String) = _state.update {
        it.copy(selected = if (imageId in it.selected) it.selected - imageId else it.selected + imageId)
    }

    fun selectAll() = _state.update { state -> state.copy(selected = state.images.map { it.id }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Uploads picked images one by one. Each image's DINOv2 feature is computed once on the server. */
    fun upload(uris: List<String>) {
        if (uris.isEmpty() || _state.value.busy) return
        _state.update { it.copy(upload = UploadProgress(0, uris.size, 0, emptyList()), message = null) }
        viewModelScope.launch {
            var lastJob: TrainingJobDto? = null
            for ((index, uri) in uris.withIndex()) {
                val image = reader.read(uri)
                val failure: String? = if (image == null) {
                    "Image ${index + 1}: could not be read or is larger than 10 MB."
                } else {
                    when (val result = repository.uploadImage(classId, image)) {
                        is AiResult.Ok -> {
                            lastJob = result.value.job ?: lastJob
                            _state.update { state -> state.copy(images = result.value.uploaded + state.images) }
                            null
                        }
                        is AiResult.Failed -> {
                            if (result.problem.stopsUploads()) {
                                val remaining = uris.size - index
                                finishUpload("${result.problem.message} ($remaining image(s) not sent.)")
                                return@launch
                            }
                            "${image.name}: ${result.problem.message}"
                        }
                    }
                }
                _state.update { state ->
                    val progress = requireNotNull(state.upload)
                    state.copy(
                        upload = progress.copy(
                            done = progress.done + 1,
                            uploaded = progress.uploaded + if (failure == null) 1 else 0,
                            failures = progress.failures + listOfNotNull(failure),
                        ),
                    )
                }
            }
            val uploaded = _state.value.upload?.uploaded ?: 0
            _state.update { it.copy(message = "Uploaded $uploaded of ${uris.size} image(s).", trainingStatus = lastJob?.status ?: it.trainingStatus) }
            refreshTrainingStatus()
        }
    }

    private fun finishUpload(failure: String) = _state.update { state ->
        val progress = requireNotNull(state.upload)
        state.copy(upload = progress.copy(done = progress.total, failures = progress.failures + failure), message = failure)
    }

    private fun AiProblem.stopsUploads() =
        this is AiProblem.Unreachable || this == AiProblem.SignInRequired || this == AiProblem.AdminRequired || this == AiProblem.AiDisabled

    fun deleteSelected() = delete(_state.value.selected.toList())

    fun deleteImage(imageId: String) = delete(listOf(imageId))

    private fun delete(ids: List<String>) {
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(deleting = true, message = null) }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            var lastProblem: AiProblem? = null
            for (id in ids) {
                when (val result = repository.deleteImage(classId, id)) {
                    is AiResult.Ok -> _state.update { state -> state.copy(images = state.images.filterNot { it.id == id }, selected = state.selected - id) }
                    is AiResult.Failed -> {
                        failed += id
                        lastProblem = result.problem
                    }
                }
            }
            val deleted = ids.size - failed.size
            _state.update {
                it.copy(
                    deleting = false,
                    message = if (failed.isEmpty()) "Deleted $deleted image(s). Their cached features were removed; the model will be rebuilt."
                    else "Deleted $deleted of ${ids.size}. ${lastProblem?.message.orEmpty()}",
                )
            }
            refreshTrainingStatus()
        }
    }

    private suspend fun refreshTrainingStatus() {
        (repository.modelStatus() as? AiResult.Ok)?.value?.let { status -> _state.update { it.copy(trainingStatus = status.status) } }
    }

    fun uploadDismissed() = _state.update { it.copy(upload = null) }

    fun messageShown() = _state.update { it.copy(message = null) }
}
