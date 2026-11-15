package com.zhelearn.garupaexport.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelearn.garupaexport.capture.CaptureRepository
import com.zhelearn.garupaexport.capture.CaptureState
import com.zhelearn.garupaexport.profile.ExportedProfile
import com.zhelearn.garupaexport.profile.ProfileRepository
import com.zhelearn.garupaexport.profile.ProfileState
import com.zhelearn.garupaexport.shizuku.ShizukuRepository
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class ExportUiState(
    val capture: CaptureState = CaptureState(),
    val profile: ProfileState = ProfileState.Empty,
    val shizuku: ShizukuStatus = ShizukuStatus.NotRunning,
)

internal class ExportViewModel(
    captures: CaptureRepository,
    private val profiles: ProfileRepository,
    shizuku: ShizukuRepository,
) : ViewModel() {
    val state = combine(captures.state, profiles.state, shizuku.status, ::ExportUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    private var pendingSave: ExportedProfile? = null

    fun retryExport() = profiles.retry()

    fun prepareSave(profile: ExportedProfile) {
        pendingSave = profile
    }

    fun takePendingSave(): ExportedProfile? = pendingSave.also { pendingSave = null }
}
