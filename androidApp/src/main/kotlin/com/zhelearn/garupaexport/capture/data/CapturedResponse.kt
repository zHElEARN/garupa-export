package com.zhelearn.garupaexport.capture.data

internal class CapturedResponse(
    val sessionId: String,
    val body: ByteArray,
    val contentEncoding: String?,
)

internal const val MAX_CAPTURE_BYTES = 32 * 1024 * 1024
