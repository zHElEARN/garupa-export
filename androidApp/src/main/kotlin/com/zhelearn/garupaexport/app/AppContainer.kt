package com.zhelearn.garupaexport.app

import android.content.Context
import com.zhelearn.garupaexport.capture.CaptureRepository
import com.zhelearn.garupaexport.capture.data.CapturedResponse
import com.zhelearn.garupaexport.profile.ProfileRepository
import com.zhelearn.garupaexport.shizuku.ShizukuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val captures = CaptureRepository()
    val shizuku = ShizukuRepository(context)
    val profiles = ProfileRepository(context, applicationScope)

    fun onCaptured(capture: CapturedResponse) {
        applicationScope.launch {
            if (captures.state.value.sessionId != capture.sessionId) return@launch
            captures.captured(capture.sessionId)
            profiles.parse(capture)
        }
    }
}
