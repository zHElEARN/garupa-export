package com.zhelearn.garupaexport.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.zhelearn.garupaexport.capture.data.CaptureProgress

internal enum class ProxyStatus { Stopped, Starting, Running, Stopping }
internal enum class CapturePhase { Idle, PreparingData, StartingProxy, SettingProxy, AwaitingProxy, VerifyingProxy, WaitingForGame, Receiving }

internal data class CaptureState(
    val status: ProxyStatus = ProxyStatus.Stopped,
    val sessionId: String? = null,
    val captured: Boolean = false,
    val error: String? = null,
    val automaticProxy: Boolean = false,
    val clearFailed: Boolean = false,
    val phase: CapturePhase = CapturePhase.Idle,
    val proxyVerified: Boolean = false,
    val verificationError: String? = null,
    val environment: ProxyEnvironment? = null,
    val progress: CaptureProgress? = null,
)

internal class CaptureRepository {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()

    fun starting() {
        mutableState.value = CaptureState(status = ProxyStatus.Starting, phase = CapturePhase.PreparingData)
    }

    fun sessionCreated(sessionId: String) {
        mutableState.update { it.copy(sessionId = sessionId) }
    }

    fun phase(phase: CapturePhase) {
        mutableState.update { it.copy(phase = phase) }
    }

    fun running(sessionId: String, automaticProxy: Boolean, error: String?, clearFailed: Boolean) =
        updateSession(sessionId) {
            it.copy(status = ProxyStatus.Running, phase = CapturePhase.AwaitingProxy, automaticProxy = automaticProxy,
                error = error, clearFailed = clearFailed)
        }

    fun checking(environment: ProxyEnvironment) {
        mutableState.update { it.copy(environment = environment, verificationError = null,
            proxyVerified = it.proxyVerified && it.environment == environment,
            phase = if (it.captured || it.progress != null || (it.proxyVerified && it.environment == environment))
                it.phase else CapturePhase.VerifyingProxy) }
    }

    fun verified(environment: ProxyEnvironment, error: String?) {
        mutableState.update { it.copy(environment = environment, proxyVerified = error == null,
            verificationError = error,
            phase = if (it.captured || it.progress != null) it.phase
                else if (error == null) CapturePhase.WaitingForGame else CapturePhase.AwaitingProxy) }
    }

    fun receiving(sessionId: String, progress: CaptureProgress?) = updateSession(sessionId) {
        if (it.captured || it.status == ProxyStatus.Stopped) it else it.copy(progress = progress,
            phase = if (progress != null) CapturePhase.Receiving
                else if (it.proxyVerified) CapturePhase.WaitingForGame else CapturePhase.AwaitingProxy)
    }

    fun stopping() {
        mutableState.update { it.copy(status = ProxyStatus.Stopping, error = null) }
    }

    fun stopFailed(message: String) {
        mutableState.update { it.copy(status = ProxyStatus.Running, phase = CapturePhase.AwaitingProxy, error = message, clearFailed = true) }
    }

    fun captured(sessionId: String) = updateSession(sessionId) { it.copy(captured = true) }

    fun failed(message: String) {
        mutableState.update { it.copy(status = ProxyStatus.Stopped, error = message) }
    }

    fun stopped() {
        mutableState.update { it.copy(status = ProxyStatus.Stopped, sessionId = null, phase = CapturePhase.Idle,
            automaticProxy = false, clearFailed = false, proxyVerified = false, progress = null) }
    }

    private fun updateSession(sessionId: String, transform: (CaptureState) -> CaptureState) {
        mutableState.update { if (it.sessionId == sessionId) transform(it) else it }
    }
}
