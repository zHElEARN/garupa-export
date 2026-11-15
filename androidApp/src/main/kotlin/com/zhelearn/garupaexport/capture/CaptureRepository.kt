package com.zhelearn.garupaexport.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class ProxyStatus { Stopped, Starting, Running, Stopping }

internal data class CaptureState(
    val status: ProxyStatus = ProxyStatus.Stopped,
    val sessionId: String? = null,
    val captured: Boolean = false,
    val error: String? = null,
    val automaticProxy: Boolean = false,
    val clearFailed: Boolean = false,
)

internal class CaptureRepository {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()

    fun starting() {
        mutableState.value = CaptureState(status = ProxyStatus.Starting)
    }

    fun sessionCreated(sessionId: String) {
        mutableState.update { it.copy(sessionId = sessionId) }
    }

    fun running(sessionId: String, automaticProxy: Boolean, error: String?, clearFailed: Boolean) =
        updateSession(sessionId) {
            it.copy(status = ProxyStatus.Running, automaticProxy = automaticProxy,
                error = error, clearFailed = clearFailed)
        }

    fun stopping() {
        mutableState.update { it.copy(status = ProxyStatus.Stopping, error = null) }
    }

    fun stopFailed(message: String) {
        mutableState.update { it.copy(status = ProxyStatus.Running, error = message, clearFailed = true) }
    }

    fun captured(sessionId: String) = updateSession(sessionId) { it.copy(captured = true) }

    fun failed(message: String) {
        mutableState.update { it.copy(status = ProxyStatus.Stopped, error = message) }
    }

    fun stopped() {
        mutableState.update { it.copy(status = ProxyStatus.Stopped, automaticProxy = false, clearFailed = false) }
    }

    private fun updateSession(sessionId: String, transform: (CaptureState) -> CaptureState) {
        mutableState.update { if (it.sessionId == sessionId) transform(it) else it }
    }
}
