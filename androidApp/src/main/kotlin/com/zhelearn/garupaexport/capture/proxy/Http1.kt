package com.zhelearn.garupaexport.capture.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Locale

/** HTTP framing only. Payload bytes are never decoded or modified. */
internal object Http1 {
    private const val MAX_HEADER = 64 * 1024

    class Head(val startLine: String, val headers: Map<String, List<String>>, val raw: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.joinToString(",")
        val status: Int get() = startLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid HTTP status line")
    }

    data class Request(val method: String, val target: String) {
        val isUserData: Boolean get() = method == "GET" &&
            Regex("^/api/suite/user/[0-9]+$").matches(
                if (target.startsWith('/')) target.substringBefore('?')
                else runCatching { URI(target).rawPath }.getOrNull().orEmpty(),
            )
    }

    data class OriginRequest(val host: String, val port: Int, val head: Head)

    /** A plain HTTP proxy request uses an absolute URL; the origin receives its path only. */
    fun toOrigin(head: Head): OriginRequest {
        val request = request(head)
        val uri = URI(request.target)
        require(uri.scheme.equals("http", true) && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null) {
            "Plain proxy requests require an absolute http URL"
        }
        val port = if (uri.port == -1) 80 else uri.port
        require(port in 1..65535) { "Invalid HTTP origin port" }
        val target = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        val removed = setOf("host", "connection", "proxy-connection", "proxy-authorization", "keep-alive")
        val headers = head.headers.filterKeys { it !in removed }.toMutableMap()
        headers["host"] = listOf(uri.rawAuthority)
        // One origin request per plain proxy connection, so changes of origin cannot be misrouted.
        headers["connection"] = listOf("close")
        val start = "${request.method} $target ${head.startLine.substringAfterLast(' ')}"
        val wire = buildString {
            append(start).append("\r\n")
            headers.forEach { (key, values) -> values.forEach { append(key).append(": ").append(it).append("\r\n") } }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return OriginRequest(uri.host.removeSurrounding("[", "]"), port, Head(start, headers, wire))
    }

    sealed interface Body {
        data object Empty : Body
        data class Fixed(val length: Long) : Body
        data object Chunked : Body
        data object UntilEof : Body
    }

    fun readHead(input: InputStream): Head? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() < MAX_HEADER) {
            val b = input.read()
            if (b == -1) {
                if (bytes.size() == 0) return null
                throw EOFException("Truncated HTTP headers")
            }
            bytes.write(b)
            matched = if (b == end[matched].toInt()) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) {
                val raw = bytes.toByteArray()
                val lines = raw.toString(Charsets.ISO_8859_1).dropLast(4).split("\r\n")
                require(lines.first().isNotBlank()) { "Missing HTTP start line" }
                val headers = linkedMapOf<String, MutableList<String>>()
                for (line in lines.drop(1)) {
                    val colon = line.indexOf(':')
                    require(colon > 0 && !line.first().isWhitespace()) { "Invalid HTTP header" }
                    headers.getOrPut(line.substring(0, colon).lowercase(Locale.ROOT)) { mutableListOf() }
                        .add(line.substring(colon + 1).trim())
                }
                return Head(lines.first(), headers, raw)
            }
        }
        error("HTTP headers exceed 64 KiB")
    }

    fun request(head: Head): Request {
        val parts = head.startLine.split(' ', limit = 3)
        require(parts.size == 3 && parts[2] in listOf("HTTP/1.0", "HTTP/1.1")) {
            "Only HTTP/1.x requests are supported"
        }
        return Request(parts[0], parts[1])
    }

    fun body(head: Head, request: Request? = null): Body {
        val response = head.startLine.startsWith("HTTP/")
        if (response && (request?.method == "HEAD" || head.status in 100..199 ||
                head.status == 204 || head.status == 304)) return Body.Empty
        val transfer = head.header("transfer-encoding")
        val lengths = head.header("content-length")?.split(',')?.map {
            it.trim().toLongOrNull()?.takeIf { length -> length >= 0 }
                ?: error("Invalid Content-Length")
        }
        require(transfer == null || lengths == null) { "Ambiguous HTTP body framing" }
        if (transfer != null) {
            if (transfer.substringAfterLast(',').trim().equals("chunked", true)) return Body.Chunked
            require(response) { "Unsupported request Transfer-Encoding" }
            return Body.UntilEof
        }
        if (lengths != null) {
            require(lengths.distinct().size == 1) { "Conflicting Content-Length values" }
            return Body.Fixed(lengths.first())
        }
        return if (response) Body.UntilEof else Body.Empty
    }

    /** Writes the original wire representation; observe receives only body payload, without chunk framing. */
    fun copyBody(
        input: InputStream,
        output: OutputStream,
        body: Body,
        observe: (ByteArray, Int) -> Unit = { _, _ -> },
    ) {
        val buffer = ByteArray(32 * 1024)
        fun copyExactly(length: Long) {
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n == -1) throw EOFException("Truncated HTTP body ($remaining bytes missing)")
                if (n == 0) continue
                output.write(buffer, 0, n)
                observe(buffer, n)
                remaining -= n
            }
        }
        when (body) {
            Body.Empty -> Unit
            is Body.Fixed -> copyExactly(body.length)
            Body.UntilEof -> {
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    observe(buffer, n)
                }
            }
            Body.Chunked -> {
                while (true) {
                    val line = readLine(input)
                    val length = line.toString(Charsets.ISO_8859_1).dropLast(2)
                        .substringBefore(';').trim().toLongOrNull(16)
                    require(length != null && length >= 0) { "Invalid HTTP chunk size" }
                    output.write(line)
                    if (length == 0L) {
                        var total = 0
                        do {
                            val trailer = readLine(input)
                            total += trailer.size
                            require(total <= MAX_HEADER) { "HTTP trailers exceed 64 KiB" }
                            output.write(trailer)
                        } while (trailer.size != 2)
                        break
                    }
                    copyExactly(length)
                    val crlf = byteArrayOf(input.read().toByte(), input.read().toByte())
                    require(crlf.contentEquals(byteArrayOf(13, 10))) { "Invalid HTTP chunk terminator" }
                    output.write(crlf)
                }
            }
        }
        output.flush()
    }

    private fun readLine(input: InputStream): ByteArray {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (line.size() < 8192) {
            val b = input.read()
            if (b == -1) throw EOFException("Truncated HTTP chunk framing")
            line.write(b)
            if (previous == 13 && b == 10) return line.toByteArray()
            previous = b
        }
        error("HTTP chunk line exceeds 8 KiB")
    }
}
