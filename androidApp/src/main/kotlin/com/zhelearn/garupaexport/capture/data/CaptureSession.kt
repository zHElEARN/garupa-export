package com.zhelearn.garupaexport.capture.data

import android.util.Log
import com.zhelearn.garupaexport.capture.proxy.Http1
import com.zhelearn.garupaexport.capture.proxy.ResponseCapture
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal data class CaptureProgress(val receivedBytes: Int, val totalBytes: Int?)

internal class CaptureSession(
    private val onProgress: (String, CaptureProgress?) -> Unit,
    private val onCaptured: (CapturedResponse) -> Unit,
) {
    val sessionId: String = UUID.randomUUID().toString()
    private enum class Status { Waiting, Receiving, Complete }
    private val status = AtomicReference(Status.Waiting)

    fun begin(response: Http1.Head): ResponseCapture? {
        if (!status.compareAndSet(Status.Waiting, Status.Receiving)) return null
        val total = if (response.header("transfer-encoding") == null)
            response.header("content-length")?.toIntOrNull()?.takeIf { it > 0 } else null
        onProgress(sessionId, CaptureProgress(0, total))
        return object : ResponseCapture {
            private val output = ByteArrayOutputStream()
            private var finished = false
            private var reportedAt = System.nanoTime()

            override fun write(bytes: ByteArray, length: Int) {
                check(!finished)
                require(output.size().toLong() + length <= MAX_CAPTURE_BYTES) {
                    "Response exceeds 32 MiB capture limit"
                }
                output.write(bytes, 0, length)
                val now = System.nanoTime()
                if (now - reportedAt >= 200_000_000L) {
                    reportedAt = now
                    onProgress(sessionId, CaptureProgress(output.size(), total))
                }
            }

            override fun complete() {
                check(!finished)
                require(output.size() > 0) { "Empty target response" }
                val captured = CapturedResponse(sessionId, output.toByteArray(), response.header("content-encoding"))
                finished = true
                status.set(Status.Complete)
                onCaptured(captured)
            }

            override fun abort() {
                if (finished) return
                finished = true
                output.reset()
                status.set(Status.Waiting)
                onProgress(sessionId, null)
                Log.w("CaptureSession", "Incomplete capture discarded")
            }
        }
    }
}
