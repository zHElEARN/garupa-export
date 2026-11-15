package com.zhelearn.garupaexport.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Platform crypto/compression; shared code receives only decrypted protobuf. */
internal object ResponseDecoder {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private val decryptKey = "wakakabiliwakaka".toByteArray(Charsets.US_ASCII)
    private val decryptIv = "biliwakakawohaha".toByteArray(Charsets.US_ASCII)

    fun decode(body: ByteArray, contentEncoding: String?): ByteArray {
        require(body.isNotEmpty() && body.size <= MAX_BYTES) { "响应体为空或超过大小限制" }
        var encrypted = body
        for (encoding in contentEncoding.orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.asReversed()) {
            encrypted = when (encoding) {
                "", "identity" -> encrypted
                "gzip", "x-gzip" -> gunzip(encrypted)
                else -> error("不支持的 HTTP 压缩格式：$encoding")
            }
        }
        require(encrypted.isNotEmpty() && encrypted.size % 16 == 0) { "响应不是完整的 AES 数据块" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptKey, "AES"), IvParameterSpec(decryptIv))
        }
        val padded = cipher.doFinal(encrypted)
        // ISO10126 only defines the final length byte; the other padding bytes are random.
        val padding = padded.last().toInt() and 0xff
        require(padding in 1..16 && padding <= padded.size) { "游戏响应解密失败：填充长度无效" }
        val decoded = padded.copyOf(padded.size - padding)
        return if (decoded.size >= 2 && decoded[0] == 0x1f.toByte() && decoded[1] == 0x8b.toByte()) {
            gunzip(decoded)
        } else decoded
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size().toLong() + count <= MAX_BYTES) { "解压后的存档超过大小限制" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
