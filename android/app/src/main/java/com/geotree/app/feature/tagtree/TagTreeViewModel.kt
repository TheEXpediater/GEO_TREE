package com.geotree.app.feature.tagtree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.camera.TreeImageStore
import com.geotree.app.core.location.FixResult
import com.geotree.app.core.location.LocationClient
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.location.LocationState
import com.geotree.app.data.model.DraftError
import com.geotree.app.data.model.TreeCode
import com.geotree.app.data.model.TreeDetails
import com.geotree.app.data.model.TreeDraft
import com.geotree.app.data.model.TreeDraftValidator
import com.geotree.app.data.repository.CreateTreeResult
import com.geotree.app.data.repository.TreeRepository
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagTreeUiState(
    val imagePath: String? = null,
    val cameraOpen: Boolean = false,
    val cameraMessage: String? = null,
    val treeCode: String = "",
    val treeCodeError: String? = null,
    val location: LocationState = LocationState.Idle,
    val detailsExpanded: Boolean = false,
    val ageText: String = "",
    val ageError: String? = null,
    val tasteCategory: String? = null,
    val yieldText: String = "",
    val yieldError: String? = null,
    val fruitQuality: String? = null,
    val notes: String = "",
    val saving: Boolean = false,
    val formError: String? = null,
    val confirmLowAccuracy: Boolean = false,
    val saved: SavedTree? = null,
)

data class SavedTree(val id: String, val treeCode: String)

class TagTreeViewModel(
    private val repository: TreeRepository,
    private val locationClient: LocationClient,
    private val imageStore: TreeImageStore,
    private val onTreeSaved: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(TagTreeUiState())
    val state: StateFlow<TagTreeUiState> = _state.asStateFlow()

    private var locationJob: Job? = null
    private var codeCheckJob: Job? = null

    init {
        // Start acquiring right away when permission already exists; otherwise wait for the user.
        if (locationClient.permission() == LocationPermission.NONE) {
            _state.update { it.copy(location = LocationState.PermissionRequired()) }
        } else {
            acquireLocation()
        }
    }

    // ---- Image ----
    fun openCamera() = _state.update { it.copy(cameraOpen = true, cameraMessage = null) }
    fun closeCamera() = _state.update { it.copy(cameraOpen = false) }
    fun onCameraPermissionDenied() =
        _state.update { it.copy(cameraMessage = "Camera permission is needed to photograph the tree.") }

    fun onImageCaptured(file: File) {
        val previous = _state.value.imagePath
        _state.update { it.copy(imagePath = file.absolutePath, cameraOpen = false, cameraMessage = null) }
        if (previous != null && previous != file.absolutePath) imageStore.delete(previous)
    }

    // ---- Tree Code ----
    /**
     * Keeps the raw text exactly as the keyboard sent it: rewriting it here (e.g. uppercasing)
     * breaks IME composition. Display is uppercased by a VisualTransformation, and the
     * validator/repository normalize on save.
     */
    fun onTreeCodeChange(value: String) {
        val raw = value.take(40)
        _state.update { it.copy(treeCode = raw, treeCodeError = null, formError = null) }
        codeCheckJob?.cancel()
        codeCheckJob = viewModelScope.launch {
            delay(300)
            val code = TreeCode.normalize(raw)
            if (code.isNotEmpty() && repository.isTreeCodeTaken(code)) {
                _state.update { it.copy(treeCodeError = "$code is already registered on this device.") }
            }
        }
    }

    // ---- Location ----
    fun onLocationPermissionResult() {
        if (locationClient.permission() == LocationPermission.NONE) {
            _state.update { it.copy(location = LocationState.PermissionRequired(denied = true)) }
        } else {
            acquireLocation()
        }
    }

    fun acquireLocation() {
        if (locationJob?.isActive == true) return
        val permission = locationClient.permission()
        if (permission == LocationPermission.NONE) {
            _state.update { it.copy(location = LocationState.PermissionRequired()) }
            return
        }
        if (!locationClient.isLocationEnabled()) {
            _state.update { it.copy(location = LocationState.LocationDisabled) }
            return
        }
        _state.update { it.copy(location = LocationState.Acquiring, formError = null) }
        locationJob = viewModelScope.launch {
            val next = when (val result = locationClient.freshFix()) {
                is FixResult.Success -> LocationState.Ready(result.fix, approximateOnly = permission == LocationPermission.APPROXIMATE)
                FixResult.PermissionDenied -> LocationState.PermissionRequired()
                FixResult.LocationDisabled -> LocationState.LocationDisabled
                FixResult.NoFix -> LocationState.Error("No GPS fix yet. Move to open sky and try again.")
                is FixResult.Failure -> LocationState.Error(result.message)
            }
            _state.update { it.copy(location = next) }
        }
    }

    // ---- Optional details ----
    fun toggleDetails() = _state.update { it.copy(detailsExpanded = !it.detailsExpanded) }
    fun onAgeChange(value: String) = _state.update { it.copy(ageText = value.filter(Char::isDigit).take(4), ageError = null) }
    fun onYieldChange(value: String) =
        _state.update { it.copy(yieldText = value.filter { c -> c.isDigit() || c == '.' }.take(8), yieldError = null) }
    fun onTasteChange(value: String?) = _state.update { it.copy(tasteCategory = value) }
    fun onFruitQualityChange(value: String?) = _state.update { it.copy(fruitQuality = value) }
    fun onNotesChange(value: String) = _state.update { it.copy(notes = value.take(2000)) }

    // ---- Save ----
    fun dismissLowAccuracyDialog() = _state.update { it.copy(confirmLowAccuracy = false) }

    fun save(confirmLowAccuracy: Boolean = false) {
        val s = _state.value
        if (s.saving || s.saved != null) return
        val age = s.ageText.takeIf { it.isNotBlank() }?.toIntOrNull()
        val yearlyYield = s.yieldText.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val ageError = if (s.ageText.isNotBlank() && age == null) DraftError.AGE_INVALID.message else null
        val yieldError = if (s.yieldText.isNotBlank() && yearlyYield == null) "Enter a number, e.g. 120.5" else null
        if (ageError != null || yieldError != null) {
            _state.update { it.copy(ageError = ageError, yieldError = yieldError, detailsExpanded = true) }
            return
        }
        val draft = TreeDraft(
            treeCode = s.treeCode,
            localImagePath = s.imagePath,
            fix = (s.location as? LocationState.Ready)?.fix,
            details = TreeDetails(age, s.tasteCategory, yearlyYield, s.fruitQuality, s.notes),
        )
        val validation = TreeDraftValidator.validate(draft)
        if (!validation.isValid) {
            showErrors(validation.errors)
            return
        }
        if (validation.lowAccuracy && !confirmLowAccuracy) {
            _state.update { it.copy(confirmLowAccuracy = true) }
            return
        }
        _state.update { it.copy(saving = true, confirmLowAccuracy = false, formError = null) }
        viewModelScope.launch {
            when (val result = repository.createTree(draft)) {
                is CreateTreeResult.Created -> {
                    _state.update { it.copy(saving = false, saved = SavedTree(result.tree.id, result.tree.treeCode)) }
                    onTreeSaved()
                }
                is CreateTreeResult.DuplicateTreeCode -> _state.update {
                    it.copy(saving = false, treeCodeError = "${result.treeCode} is already registered on this device.")
                }
                is CreateTreeResult.Invalid -> {
                    _state.update { it.copy(saving = false) }
                    showErrors(result.errors)
                }
            }
        }
    }

    private fun showErrors(errors: Set<DraftError>) {
        val codeError = errors.firstOrNull { it == DraftError.TREE_CODE_BLANK || it == DraftError.TREE_CODE_FORMAT }
        val other = errors.filterNot { it == DraftError.TREE_CODE_BLANK || it == DraftError.TREE_CODE_FORMAT }
        _state.update {
            it.copy(
                treeCodeError = codeError?.message ?: it.treeCodeError,
                formError = other.joinToString("\n") { e -> e.message }.ifEmpty { null },
            )
        }
    }

    override fun onCleared() {
        // A photo taken for a tag that was never saved is an orphan; remove it.
        val s = _state.value
        if (s.saved == null) imageStore.delete(s.imagePath)
    }
}
