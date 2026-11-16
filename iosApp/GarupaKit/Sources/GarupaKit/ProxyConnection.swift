import Foundation
import NIOCore
import NIOPosix
import NIOHTTP1
import NIOSSL

// Accessed only on the proxy's one event loop.
final class Connections: @unchecked Sendable {
    var channels: [ObjectIdentifier: Channel] = [:]
    func add(_ channel: Channel) {
        let id = ObjectIdentifier(channel)
        channels[id] = channel
        channel.closeFuture.whenComplete { [weak self] _ in self?.channels.removeValue(forKey: id) }
    }
}

final class IdleCloser: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if event is IdleStateHandler.IdleStateEvent { context.close(promise: nil) }
        else { context.fireUserInboundEventTriggered(event) }
    }
}

final class ProxyHandshake: ChannelInboundHandler, RemovableChannelHandler {
    typealias InboundIn = ByteBuffer
    typealias OutboundOut = ByteBuffer
    private let authority: CertificateAuthority
    private let connections: Connections
    private let slot: CaptureSlot
    private let probe: ProxyProbe
    private let onError: (String) -> Void
    private var buffer: ByteBuffer?
    private var connecting = false
    private var upstream: Channel?
    private var targetAPI = false

    init(authority: CertificateAuthority, connections: Connections, slot: CaptureSlot,
         probe: ProxyProbe, onError: @escaping (String) -> Void) {
        self.authority = authority
        self.connections = connections
        self.slot = slot
        self.probe = probe
        self.onError = onError
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var incoming = unwrapInboundIn(data)
        if buffer == nil { buffer = context.channel.allocator.buffer(capacity: 4096) }
        buffer?.writeBuffer(&incoming)
        guard let pending = buffer, pending.readableBytes <= 256 * 1024 else {
            fail(context, GarupaError.message("代理请求超过大小限制")); return
        }
        guard !connecting else { return }
        let bytes = pending.readableBytesView
        let marker: [UInt8] = [13, 10, 13, 10]
        guard let end = bytes.indices.first(where: { index in
            index + 4 <= bytes.endIndex && Array(bytes[index..<(index + 4)]) == marker
        }) else {
            if pending.readableBytes > 64 * 1024 { fail(context, GarupaError.message("代理请求头过大")) }
            return
        }
        do {
            let headerLength = end - bytes.startIndex + 4
            let head = String(decoding: bytes.prefix(headerLength), as: UTF8.self)
            let fields = head.components(separatedBy: "\r\n")[0].split(separator: " ")
            guard fields.count == 3, fields[2] == "HTTP/1.1" || fields[2] == "HTTP/1.0" else {
                throw GarupaError.message("不支持的代理请求")
            }
            let connect = fields[0] == "CONNECT"
            let destination = String(fields[1])
            guard let url = URL(string: connect ? "https://\(destination)" : destination),
                  let urlHost = url.host, url.user == nil, url.password == nil, url.fragment == nil,
                  connect ? url.path.isEmpty && url.query == nil : url.scheme == "http" else {
                throw GarupaError.message("代理目标地址无效")
            }
            let host = urlHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased()
            if !connect, probe.matches(method: String(fields[0]), url: url) {
                connecting = true
                var response = context.channel.allocator.buffer(capacity: 256)
                response.writeString("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: \(probe.response.utf8.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                response.writeString(probe.response)
                context.writeAndFlush(wrapOutboundOut(response)).whenComplete { _ in context.close(promise: nil) }
                return
            }
            let port = url.port ?? (connect ? 443 : 80)
            guard (1...65535).contains(port),
                  !(port == LocalProxy.port && ["localhost", "127.0.0.1", "::1"].contains(host)) else {
                throw GarupaError.message("代理目标端口无效")
            }
            targetAPI = connect && host.range(of: #"^l[0-9]+-prod-all-bd\.bilibiligame\.net$"#, options: .regularExpression) != nil
            connecting = true
            if connect { buffer?.moveReaderIndex(forwardBy: headerLength) }
            _ = context.channel.setOption(ChannelOptions.autoRead, value: false)
            let tls = targetAPI ? try authority.serverContext(host: host) : nil
            openUpstream(context: context, host: host, port: port, connect: connect, tls: tls)
        } catch { fail(context, error) }
    }

    private func openUpstream(context: ChannelHandlerContext, host: String, port: Int,
                              connect: Bool, tls: NIOSSLContext?) {
        let pair = HTTPPair(capture: tls == nil ? nil : slot, onError: onError)
        let client = context.channel
        let bootstrap = ClientBootstrap(group: client.eventLoop)
            .connectTimeout(.seconds(20))
            .channelOption(ChannelOptions.socketOption(.tcp_nodelay), value: 1)
            .channelOption(ChannelOptions.autoRead, value: false)
            .channelInitializer { [connections] remote in
                connections.add(remote)
                if connect && tls == nil {
                    return remote.pipeline.addHandler(Tunnel(peer: client))
                }
                do {
                    var handlers: [ChannelHandler] = []
                    if tls != nil {
                        var configuration = TLSConfiguration.makeClientConfiguration()
                        configuration.applicationProtocols = ["http/1.1"]
                        configuration.minimumTLSVersion = .tlsv12
                        let upstreamTLS = try NIOSSLContext(configuration: configuration)
                        handlers.append(try NIOSSLClientHandler(context: upstreamTLS, serverHostname: host))
                    }
                    handlers += [HTTPRequestEncoder(), ByteToMessageHandler(HTTPResponseDecoder(leftOverBytesStrategy: .dropBytes)),
                                 ResponseForwarder(client: client, pair: pair)]
                    return remote.pipeline.addHandlers(handlers)
                } catch { return remote.eventLoop.makeFailedFuture(error) }
            }
        bootstrap.connect(host: host, port: port).flatMap { remote in
            self.upstream = remote
            guard client.isActive else { return remote.close() }
            let ready: EventLoopFuture<Void>
            if connect {
                var response = client.allocator.buffer(capacity: 64)
                response.writeString("HTTP/1.1 200 Connection Established\r\n\r\n")
                ready = client.writeAndFlush(response)
            } else { ready = client.eventLoop.makeSucceededVoidFuture() }
            return ready.flatMap {
                do {
                    var handlers: [ChannelHandler] = []
                    if let tls { handlers.append(NIOSSLServerHandler(context: tls)) }
                    if connect && tls == nil { handlers.append(Tunnel(peer: remote)) }
                    else {
                        handlers += [ByteToMessageHandler(HTTPRequestDecoder(leftOverBytesStrategy: .dropBytes)), HTTPResponseEncoder(),
                                     RequestForwarder(remote: remote, pair: pair, plainHost: connect ? nil : host, port: port)]
                    }
                    return client.pipeline.addHandlers(handlers)
                }
            }.flatMap {
                client.pipeline.removeHandler(self)
            }.flatMap {
                // Replay bytes that arrived with CONNECT through the newly installed TLS handler.
                if let pending = self.buffer, pending.readableBytes > 0 {
                    client.pipeline.fireChannelRead(pending)
                    client.pipeline.fireChannelReadComplete()
                }
                self.buffer = nil
                return client.setOption(ChannelOptions.autoRead, value: true).and(
                    remote.setOption(ChannelOptions.autoRead, value: true)
                ).map { _ in }
            }
        }.whenFailure { error in self.fail(context, error) }
    }

    private func fail(_ context: ChannelHandlerContext, _ error: Error) {
        if targetAPI { onError("游戏 API 连接失败：\(error.localizedDescription)") }
        upstream?.close(promise: nil)
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        upstream?.close(promise: nil)
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) { fail(context, error) }
}

final class Tunnel: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer
    let peer: Channel
    private var lastWrite: EventLoopFuture<Void>?
    init(peer: Channel) { self.peer = peer }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        lastWrite = peer.writeAndFlush(unwrapInboundIn(data))
    }
    func channelWritabilityChanged(context: ChannelHandlerContext) {
        _ = peer.setOption(ChannelOptions.autoRead, value: context.channel.isWritable)
    }
    func channelInactive(context: ChannelHandlerContext) {
        if let lastWrite { lastWrite.whenComplete { _ in self.peer.close(promise: nil) } }
        else { peer.close(promise: nil) }
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) { context.close(promise: nil); peer.close(promise: nil) }
}
