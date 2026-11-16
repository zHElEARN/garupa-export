package com.zhelearn.garupaexport.capture.proxy

import android.annotation.SuppressLint
import android.os.Build
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal interface ResponseCapture {
    fun write(bytes: ByteArray, length: Int)
    fun complete()
    fun abort()
}

internal class ProxyServer(
    private val authority: CertificateAuthority,
    private val log: (String) -> Unit,
    private val capture: (Http1.Head) -> ResponseCapture?,
    private val probe: ProxyProbe,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    // Classify the request before admission. Busy SDK/background tunnels must not
    // consume the API's reserved capacity. Header readers are separately bounded.
    private val headerSlots = Semaphore(64)
    private val otherSlots = Semaphore(128)
    private val apiSlots = Semaphore(16)
    private var listener: ServerSocket? = null

    fun start() {
        check(running.compareAndSet(false, true))
        try {
            listener = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 32)
            }
            workers.execute {
                while (running.get()) {
                    try {
                        val client = checkNotNull(listener).accept()
                        if (!headerSlots.tryAcquire()) {
                            client.close()
                            log("等待代理请求头的连接达到上限")
                            continue
                        }
                        sockets.add(client)
                        try {
                            workers.execute {
                                try { handle(client) } finally {
                                    sockets.remove(client)
                                    runCatching { client.close() }
                                }
                            }
                        } catch (error: Exception) {
                            sockets.remove(client)
                            client.close()
                            headerSlots.release()
                            throw error
                        }
                    } catch (error: Exception) {
                        if (running.get()) log("接收连接失败：${error.javaClass.simpleName}")
                    }
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun handle(client: Socket) {
        var established = false
        var upstream: Socket? = null
        var headerPending = true
        var lane: Semaphore? = null
        try {
            client.soTimeout = 10_000
            client.tcpNoDelay = true
            // Do not buffer CONNECT: read exactly its headers, leaving ClientHello on the socket.
            val head = Http1.readHead(client.getInputStream()) ?: return
            headerSlots.release()
            headerPending = false
            client.soTimeout = 120_000
            val request = Http1.request(head)
            if (probe.respond(head, client.getOutputStream())) return
            if (request.method != "CONNECT") {
                val origin = Http1.toOrigin(head)
                rejectLoop(origin.host, origin.port)
                lane = admit(client, api = false) ?: return
                upstream = atPhase("HTTP 上游连接 (${origin.host})") { openUpstream(origin.host, origin.port) }
                established = true
                atPhase("HTTP 转发 (${origin.host})") {
                    relayHttp(client, upstream, firstHead = origin.head, singleRequest = true, allowCapture = false)
                }
                return
            }
            val uri = URI("https://${request.target}")
            val host = requireNotNull(uri.host).removeSurrounding("[", "]").lowercase(Locale.ROOT)
            val port = if (uri.port == -1) 443 else uri.port
            require(uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null &&
                uri.rawFragment == null && port in 1..65535) { "Invalid CONNECT authority" }
            rejectLoop(host, port)
            val api = intercepts(host)
            if (api) log("收到目标 CONNECT $host")
            lane = admit(client, api) ?: return
            if (!api) {
                upstream = atPhase("上游连接 ($host)") { openUpstream(host, port) }
                client.getOutputStream().write(CONNECT_OK)
                client.getOutputStream().flush()
                established = true
                atPhase("透传传输 ($host)") { tunnel(client, upstream) }
                return
            }
            val serverContext = atPhase("本地证书 ($host)") { authority.serverContext(host) }
            client.getOutputStream().write(CONNECT_OK)
            client.getOutputStream().flush()
            established = true
            val clientTls = atPhase("客户端 TLS ($host)") {
                serverContext.socketFactory.createSocket(client, host, port, false) as SSLSocket
            }
            sockets.add(clientTls)
            try {
                // Complete client TLS before opening the upstream connection.
                atPhase("客户端 TLS ($host)") {
                    configureTls(clientTls, server = true)
                    clientTls.startHandshake()
                }
                log("客户端 TLS 已连接 $host")
                upstream = atPhase("上游连接 ($host)") { openUpstream(host, port) }
                val upstreamTls = atPhase("上游 TLS ($host)") {
                    SSLContext.getDefault().socketFactory.createSocket(upstream, host, port, true) as SSLSocket
                }
                sockets.add(upstreamTls)
                try {
                    atPhase("上游 TLS ($host)") {
                        configureTls(upstreamTls, server = false)
                        upstreamTls.startHandshake()
                    }
                    log("游戏 API 已连接 $host")
                    atPhase("HTTP 转发 ($host)") { relayHttp(clientTls, upstreamTls) }
                } finally {
                    sockets.remove(upstreamTls)
                    runCatching { upstreamTls.close() }
                }
            } finally {
                sockets.remove(clientTls)
                runCatching { clientTls.close() }
            }
        } catch (error: Exception) {
            val failure = error as? ProxyFailure
            val cause = failure?.error ?: error
            val phase = failure?.phase ?: "代理请求"
            if (running.get() && cause !is EOFException) {
                log("$phase 失败：${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(240)}")
            }
            if (!established) runCatching {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                )
            }
        } finally {
            if (headerPending) headerSlots.release()
            lane?.release()
            upstream?.let {
                sockets.remove(it)
                runCatching { it.close() }
            }
        }
    }

    private class ProxyFailure(val phase: String, val error: Exception) : Exception(error)

    private inline fun <T> atPhase(phase: String, operation: () -> T): T = try {
        operation()
    } catch (error: Exception) {
        throw ProxyFailure(phase, error)
    }

    private fun admit(client: Socket, api: Boolean): Semaphore? {
        val lane = if (api) apiSlots else otherSlots
        if (lane.tryAcquire()) return lane
        log(if (api) "存档 API 并发名额已满" else "普通转发并发名额已满，存档 API 名额独立保留")
        client.getOutputStream().write(
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        client.getOutputStream().flush()
        return null
    }

    private fun rejectLoop(host: String, port: Int) {
        require(!(port == PORT && host in listOf("127.0.0.1", "localhost", "::1"))) { "Proxy loop rejected" }
    }

    private fun openUpstream(host: String, port: Int): Socket {
        val socket = Socket()
        sockets.add(socket)
        try {
            socket.connect(InetSocketAddress(host, port), 20_000)
            socket.soTimeout = 120_000
            socket.tcpNoDelay = true
            return socket
        } catch (error: Exception) {
            sockets.remove(socket)
            socket.close()
            throw error
        }
    }

    @SuppressLint("NewApi")
    private fun configureTls(socket: SSLSocket, server: Boolean) {
        socket.useClientMode = !server
        socket.soTimeout = 120_000
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        socket.sslParameters = socket.sslParameters.apply {
            if (!server) endpointIdentificationAlgorithm = "HTTPS"
            if (Build.VERSION.SDK_INT >= 29) applicationProtocols = arrayOf("http/1.1")
        }
    }

    private fun tunnel(client: Socket, upstream: Socket) {
        val down = workers.submit {
            try { upstream.getInputStream().copyTo(client.getOutputStream()) }
            catch (error: Exception) {
                // A failed downstream otherwise leaves the client reader blocked
                // for its entire idle timeout, retaining an admission permit.
                runCatching { client.close() }
                runCatching { upstream.close() }
                throw error
            }
            finally { runCatching { client.shutdownOutput() } }
        }
        try {
            client.getInputStream().copyTo(upstream.getOutputStream())
            runCatching { upstream.shutdownOutput() }
            down.get(125, TimeUnit.SECONDS)
        } finally {
            runCatching { upstream.close() }
            down.cancel(true)
        }
    }

    private sealed interface Queued {
        data class Message(val request: Http1.Request) : Queued
        data object End : Queued
    }

    private fun relayHttp(
        client: Socket,
        upstream: Socket,
        firstHead: Http1.Head? = null,
        singleRequest: Boolean = false,
        allowCapture: Boolean = true,
    ) {
        val queue = LinkedBlockingQueue<Queued>(32)
        val upInput = upstream.getInputStream().buffered()
        val downOutput = client.getOutputStream()
        val requests = workers.submit {
            try {
                val input = client.getInputStream().buffered()
                val output = upstream.getOutputStream()
                var pendingHead = firstHead
                while (running.get()) {
                    val head = pendingHead ?: Http1.readHead(input) ?: break
                    pendingHead = null
                    val request = Http1.request(head)
                    val body = Http1.body(head)
                    queue.put(Queued.Message(request))
                    output.write(head.raw)
                    output.flush() // Expect: 100-continue must reach the upstream before reading its body.
                    Http1.copyBody(input, output, body)
                    if (singleRequest) break
                }
                queue.put(Queued.End)
                runCatching { upstream.shutdownOutput() }
            } catch (error: Exception) {
                if (running.get() && !client.isClosed && error !is EOFException) {
                    log("请求流结束：${error.javaClass.simpleName}")
                }
                runCatching { upstream.close() }
            }
        }
        try {
            while (running.get()) {
                val queued = queue.poll(1, TimeUnit.SECONDS)
                if (queued == null) {
                    if (requests.isDone) break
                    continue
                }
                if (queued == Queued.End) break
                val request = (queued as Queued.Message).request
                var response: Http1.Head
                while (true) {
                    response = Http1.readHead(upInput) ?: throw EOFException("Missing HTTP response")
                    require(response.status != 101) { "HTTP protocol upgrades are not supported" }
                    // Validate framing before exposing the response to the client.
                    Http1.body(response, request)
                    downOutput.write(response.raw)
                    downOutput.flush()
                    if (response.status !in 100..199) break
                }
                val body = Http1.body(response, request)
                var sink = if (allowCapture && request.isUserData && response.status == 200) {
                    runCatching { capture(response) }.getOrElse {
                        log("无法开始接收存档：${it.javaClass.simpleName}")
                        null
                    }
                } else null
                try {
                    Http1.copyBody(upInput, downOutput, body) { bytes, length ->
                        try { sink?.write(bytes, length) } catch (error: Exception) {
                            sink?.abort()
                            sink = null
                            log("接收存档失败：${error.javaClass.simpleName}；继续转发")
                        }
                    }
                    sink?.complete()
                } finally { sink?.abort() }
                if (singleRequest || body == Http1.Body.UntilEof || response.header("connection").equals("close", true)) break
            }
        } finally {
            runCatching { client.close() }
            runCatching { upstream.close() }
            requests.cancel(true)
        }
    }

    override fun close() {
        running.set(false)
        runCatching { listener?.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    companion object {
        const val PORT = 8888
        private val CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
        // SDK login can also live under bilibiligame.net and uses a different TLS
        // verification path. Only the confirmed CN game API host family is MITM'd.
        fun intercepts(host: String): Boolean = API_HOST.matches(host)
        private val API_HOST = Regex("^l[0-9]+-prod-all-bd\\.bilibiligame\\.net$")
    }
}
