package com.zhelearn.garupaexport.capture.data

import android.util.Log
import com.zhelearn.garupaexport.capture.proxy.Http1
import com.zhelearn.garupaexport.capture.proxy.ResponseCapture
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class CaptureSession(private val onCaptured: (CapturedResponse) -> Unit) {
    val sessionId: String = UUID.randomUUID().toString()
    private enum class Status { Waiting, Receiving, Complete }
    private val status = AtomicReference(Status.Waiting)

    fun begin(response: Http1.Head): ResponseCapture? {
        if (!status.compareAndSet(Status.Waiting, Status.Receiving)) return null
        return object : ResponseCapture {
            private val output = ByteArrayOutputStream()
            private var finished = false

            override fun write(bytes: ByteArray, length: Int) {
                check(!finished)
                require(output.size().toLong() + length <= MAX_CAPTURE_BYTES) {
                    "Response exceeds 32 MiB capture limit"
                }
                output.write(bytes, 0, length)
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
                Log.w("CaptureSession", "Incomplete capture discarded")
            }
        }
    }
}
