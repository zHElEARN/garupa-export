package com.zhelearn.garupaexport.catalog

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Fetches and validates a snapshot without changing the persistent cache. */
internal class CatalogClient {
    fun download(previous: CatalogCache?): CatalogCache {
        // Downloads must also work when a stale loopback proxy is configured.
        val connection = URL(CardCatalog.URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "GarupaExport/1.0")
            previous?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            previous?.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return checkNotNull(previous) { "Bestdori 未返回卡牌数据" }
                HttpURLConnection.HTTP_OK -> Unit
                429 -> error("Bestdori 请求过于频繁，请等待服务器允许后再刷新")
                else -> error("Bestdori 下载失败（HTTP $status）")
            }
            require(connection.contentLengthLong <= CardCatalog.MAX_JSON_BYTES) { "Bestdori 卡牌数据超过大小限制" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size().toLong() + count <= CardCatalog.MAX_JSON_BYTES) { "Bestdori 卡牌数据超过大小限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return CatalogCache(bytes.toString(Charsets.UTF_8), System.currentTimeMillis(),
                connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally { connection.disconnect() }
    }
}
