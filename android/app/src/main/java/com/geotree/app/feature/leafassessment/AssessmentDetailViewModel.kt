package com.geotree.app.feature.leafassessment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.core.database.LeafAssessmentEntity
import com.geotree.app.data.repository.LeafAssessmentRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AssessmentLoad {
    data object Loading : AssessmentLoad
    data object Missing : AssessmentLoad
    data class Loaded(val record: LeafAssessmentEntity, val treeCode: String?) : AssessmentLoad
}

/** "Assess this photo again" is offered for a finished scan whose photo is still on the device. */
fun canReassess(record: LeafAssessmentEntity, fileExists: (String) -> Boolean = { File(it).isFile }): Boolean =
    record.status.isFinal && record.imageLocalPath?.let(fileExists) == true

/**
 * One scan, read from Room (works offline). Used for the result page right after a scan and
 * for read-only history entries. It never edits the record: "assess again" makes a new scan.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssessmentDetailViewModel(
    assessmentId: String,
    private val repository: LeafAssessmentRepository,
    treeCodeOf: (treeId: String) -> Flow<String?>,
) : ViewModel() {
    val load: StateFlow<AssessmentLoad> = repository.observeAssessment(assessmentId)
        .flatMapLatest { record ->
            if (record == null) flowOf(AssessmentLoad.Missing)
            else combine(flowOf(record), treeCodeOf(record.treeId)) { r, code -> AssessmentLoad.Loaded(r, code) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssessmentLoad.Loading)

    private val _newAssessmentId = MutableStateFlow<String?>(null)
    /** Set when "assess again" recorded a new scan; the screen opens its result page. */
    val newAssessmentId: StateFlow<String?> = _newAssessmentId.asStateFlow()

    private val _reassessing = MutableStateFlow(false)
    val reassessing: StateFlow<Boolean> = _reassessing.asStateFlow()

    fun reassess() {
        val record = (load.value as? AssessmentLoad.Loaded)?.record ?: return
        if (_reassessing.value) return
        _reassessing.value = true
        viewModelScope.launch {
            _newAssessmentId.value = repository.reassess(record.id)?.id
            _reassessing.value = false
        }
    }

    fun navigationHandled() = _newAssessmentId.update { null }
}
