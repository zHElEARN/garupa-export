package com.zhelearn.garupaexport.profile

import android.util.Log
import com.zhelearn.garupaexport.capture.data.CapturedResponse
import com.zhelearn.garupaexport.catalog.CardCatalog
import com.zhelearn.garupaexport.catalog.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ExportedProfile(
    val json: String,
    val fileName: String,
    val name: String,
    val cardCount: Int,
    val areaItemCount: Int,
    val potentialCount: Int,
    val warnings: List<String>,
)

internal sealed interface ProfileState {
    data object Empty : ProfileState
    data object Parsing : ProfileState
    data class Ready(val profile: ExportedProfile) : ProfileState
    data class Failed(val message: String) : ProfileState
}

internal class ProfileRepository(
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ProfileState>(ProfileState.Empty)
    val state = mutableState.asStateFlow()
    private var parseJob: Job? = null
    private var latestCapture: CapturedResponse? = null

    fun reset() {
        parseJob?.cancel()
        parseJob = null
        latestCapture = null
        mutableState.value = ProfileState.Empty
    }

    // Called on the main thread; IO work outlives individual activities.
    fun parse(capture: CapturedResponse) {
        if (mutableState.value == ProfileState.Parsing && latestCapture === capture) return
        parseJob?.cancel()
        latestCapture = capture
        mutableState.value = ProfileState.Parsing
        parseJob = scope.launch {
            try {
                val data = checkNotNull(catalog.state.value.cache) { "请先下载 Bestdori 卡牌数据" }.catalog
                val profile = withContext(Dispatchers.IO) { export(capture, data) }
                mutableState.value = ProfileState.Ready(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ProfileRepository", "Profile export failed", error)
                mutableState.value = ProfileState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun retry() {
        latestCapture?.let(::parse)
    }

    private fun export(capture: CapturedResponse, data: CardCatalog): ExportedProfile {
        val account = GameAccountParser.parse(ResponseDecoder.decode(capture.body, capture.contentEncoding))
        val result = BestdoriExporter.export(account, data)
        return ExportedProfile(
            json = result.profile.toJsonString(),
            fileName = "bestdori-${capture.sessionId}.json",
            name = result.profile.name,
            cardCount = result.profile.cards.size,
            areaItemCount = result.profile.items.values.sumOf { levels -> levels.count { it != null } },
            potentialCount = account.potentials.size,
            warnings = result.warnings,
        )
    }
}
