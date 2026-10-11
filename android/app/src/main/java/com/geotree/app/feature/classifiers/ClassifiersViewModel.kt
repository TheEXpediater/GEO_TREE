package com.geotree.app.feature.classifiers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.data.remote.ClassifierClassDto
import com.geotree.app.data.remote.ModelStatusDto
import com.geotree.app.data.repository.AiProblem
import com.geotree.app.data.repository.AiResult
import com.geotree.app.data.repository.ClassifierRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ClassifiersUiState(
    val loading: Boolean = true,
    val classes: List<ClassifierClassDto> = emptyList(),
    val minImagesPerClass: Int = 0,
    val model: ModelStatusDto? = null,
    /** Load failure: the screen shows it instead of the list (e.g. offline, not an admin). */
    val problem: AiProblem? = null,
    /** Error for the open add/rename dialog (e.g. a duplicate name). */
    val dialogError: String? = null,
    val working: Boolean = false,
    /** One-off feedback, shown once then cleared. */
    val message: String? = null,
) {
    val datasetImageCount: Int get() = model?.datasetImageCount ?: classes.sumOf { it.imageCount }
}

/** Model training and feature extraction in progress on the server: worth polling. */
fun ClassifiersUiState.isServerBusy(): Boolean =
    model?.status in BUSY_STATUSES || classes.any { it.status == "PENDING_FEATURES" }

private val BUSY_STATUSES = setOf("QUEUED", "TRAINING")

class ClassifiersViewModel(
    private val repository: ClassifierRepository,
    private val pollIntervalMillis: Long = 3_000,
) : ViewModel() {
    private val _state = MutableStateFlow(ClassifiersUiState())
    val state: StateFlow<ClassifiersUiState> = _state.asStateFlow()
    private var polling: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load(showSpinner = _state.value.classes.isEmpty()) }
    }

    private suspend fun load(showSpinner: Boolean) {
        if (showSpinner) _state.update { it.copy(loading = true) }
        when (val classes = repository.classes()) {
            is AiResult.Failed -> _state.update { it.copy(loading = false, problem = classes.problem) }
            is AiResult.Ok -> {
                val model = repository.modelStatus()
                _state.update {
                    it.copy(
                        loading = false,
                        problem = null,
                        classes = classes.value.items,
                        minImagesPerClass = classes.value.minImagesPerClass,
                        model = (model as? AiResult.Ok)?.value ?: it.model,
                    )
                }
            }
        }
    }

    /** Polls only while the screen is visible and the server is still working. */
    fun setVisible(visible: Boolean) {
        polling?.cancel()
        polling = null
        if (!visible) return
        refresh()
        polling = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                if (_state.value.isServerBusy()) load(showSpinner = false)
            }
        }
    }

    fun addClass(name: String, onDone: () -> Unit) {
        val error = nameError(name, exceptId = null)
        if (error != null) return _state.update { it.copy(dialogError = error) }
        mutate(onDone) { repository.createClass(name) to "Added ${name.trim()}." }
    }

    /** Renaming keeps the class id, so its images, cached features and model mapping are unaffected. */
    fun renameClass(id: String, name: String, onDone: () -> Unit) {
        val current = _state.value.classes.firstOrNull { it.id == id }
        if (current != null && current.name == name.trim()) return onDone()
        val error = nameError(name, exceptId = id)
        if (error != null) return _state.update { it.copy(dialogError = error) }
        mutate(onDone) { repository.renameClass(id, name) to "Renamed to ${name.trim()}." }
    }

    /** Checked locally for instant feedback; the server enforces the same rules. */
    private fun nameError(raw: String, exceptId: String?): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> "Enter a classifier name."
            _state.value.classes.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } ->
                "A classifier named $name already exists."
            else -> null
        }
    }

    fun deleteClass(item: ClassifierClassDto) = mutate({}) {
        val result = repository.deleteClass(item.id)
        val message = (result as? AiResult.Ok)?.value?.let {
            "Deleted ${item.name} and ${it.deletedImages} image(s)." +
                if (it.modelInvalidated) " The model used this class and must be rebuilt." else ""
        }
        result to (message ?: "")
    }

    fun retrain() = mutate({}) {
        val result = repository.retrain()
        result to "Training queued. The current model stays active until the new one is validated."
    }

    private fun mutate(onDone: () -> Unit, action: suspend () -> Pair<AiResult<*>, String>) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, dialogError = null) }
        viewModelScope.launch {
            val (result, success) = action()
            when (result) {
                is AiResult.Ok -> {
                    _state.update { it.copy(working = false, message = success) }
                    onDone()
                    load(showSpinner = false)
                }
                is AiResult.Failed -> _state.update {
                    it.copy(working = false, dialogError = result.problem.message, message = result.problem.message)
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun dialogClosed() = _state.update { it.copy(dialogError = null) }
}
