package com.zhelearn.garupaexport.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelearn.garupaexport.capture.CaptureRepository
import com.zhelearn.garupaexport.capture.CaptureState
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.capture.guide.guideStage
import com.zhelearn.garupaexport.catalog.CatalogRepository
import com.zhelearn.garupaexport.catalog.CatalogState
import com.zhelearn.garupaexport.profile.ExportedProfile
import com.zhelearn.garupaexport.profile.ProfileRepository
import com.zhelearn.garupaexport.profile.ProfileState
import com.zhelearn.garupaexport.shizuku.ShizukuRepository
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal data class ExportUiState(
    val capture: CaptureState = CaptureState(),
    val profile: ProfileState = ProfileState.Empty,
    val shizuku: ShizukuStatus = ShizukuStatus.NotRunning,
    val catalog: CatalogState = CatalogState(),
    val notificationsEnabled: Boolean = true,
) {
    val stage get() = guideStage(capture, profile)
    val canStart get() = capture.status == ProxyStatus.Stopped && profile != ProfileState.Parsing
}

internal class ExportViewModel(
    captures: CaptureRepository,
    private val profiles: ProfileRepository,
    shizuku: ShizukuRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val notificationsEnabled = MutableStateFlow(true)
    val state = combine(captures.state, profiles.state, shizuku.status, catalog.state,
        notificationsEnabled, ::ExportUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    private var pendingSave: ExportedProfile? = null
    private val messageQueue = Channel<String>(Channel.BUFFERED)
    val messages = messageQueue.receiveAsFlow()

    fun showMessage(message: String) { messageQueue.trySend(message) }

    fun retryExport() = profiles.retry()
    fun refreshAndRetryExport() = profiles.retry(refreshCatalog = true)
    fun refreshCatalog() = catalog.refresh()
    fun updateNotificationPermission(enabled: Boolean) { notificationsEnabled.value = enabled }

    fun prepareSave(profile: ExportedProfile): Boolean {
        if (pendingSave != null) return false
        pendingSave = profile
        return true
    }

    fun takePendingSave(): ExportedProfile? = pendingSave.also { pendingSave = null }
}
