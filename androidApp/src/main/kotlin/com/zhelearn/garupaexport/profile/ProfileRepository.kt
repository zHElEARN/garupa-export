package com.zhelearn.garupaexport.profile

import android.content.Context
import android.util.Log
import com.zhelearn.garupaexport.capture.data.CapturedResponse
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
)

internal sealed interface ProfileState {
    data object Empty : ProfileState
    data object Parsing : ProfileState
    data class Ready(val profile: ExportedProfile) : ProfileState
    data class Failed(val message: String) : ProfileState
}

internal class ProfileRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ProfileState>(ProfileState.Empty)
    val state = mutableState.asStateFlow()
    private var parseJob: Job? = null
    private var latestCapture: CapturedResponse? = null
    private val master by lazy {
        context.assets.open("master-episodes.json").bufferedReader().use { MasterData.fromJson(it.readText()) }
    }

    // Called on the main thread; IO work outlives individual activities.
    fun parse(capture: CapturedResponse) {
        if (mutableState.value == ProfileState.Parsing && latestCapture === capture) return
        parseJob?.cancel()
        latestCapture = capture
        mutableState.value = ProfileState.Parsing
        parseJob = scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { export(capture) }
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

    private fun export(capture: CapturedResponse): ExportedProfile {
        val account = GameAccountParser.parse(ResponseDecoder.decode(capture.body, capture.contentEncoding))
        val result = BestdoriExporter.export(account, master)
        return ExportedProfile(
            json = result.profile.toJsonString(),
            fileName = "bestdori-${capture.sessionId}.json",
            name = result.profile.name,
            cardCount = result.profile.cards.size,
            areaItemCount = result.profile.items.values.sumOf { levels -> levels.count { it != null } },
            potentialCount = account.potentials.size,
        )
    }
}
