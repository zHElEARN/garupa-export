package com.zhelearn.garupaexport.capture.proxy

import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.util.UUID

/** A session-local endpoint served by the proxy itself, without DNS, upstream TLS or game traffic. */
internal class ProxyProbe {
    private val path = "/proxy-check/${UUID.randomUUID()}"
    val response = UUID.randomUUID().toString()

    fun requestUrl(): URL = URL("http://$HOST$path?nonce=${UUID.randomUUID()}")

    fun respond(head: Http1.Head, output: OutputStream): Boolean {
        val request = Http1.request(head)
        val uri = runCatching { URI(request.target) }.getOrNull() ?: return false
        if (request.method != "GET" || uri.scheme != "http" || uri.host != HOST ||
            uri.rawPath != path || uri.port != -1 || uri.rawUserInfo != null) return false
        val body = response.toByteArray(Charsets.US_ASCII)
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        return true
    }

    companion object {
        const val HOST = "garupa-export.invalid"
    }
}
