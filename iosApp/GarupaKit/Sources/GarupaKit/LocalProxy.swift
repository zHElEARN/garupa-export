import Foundation
import NIOCore
import NIOPosix

/// Explicit HTTP proxy. All channel state is confined to a single event loop.
public final class LocalProxy {
    public static let port = 8888
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private let connections = Connections()
    private let authority: CertificateAuthority
    private let onCapture: (CapturedResponse) -> Void
    private let onError: (String) -> Void
    private var listener: Channel?

    public init(authority: CertificateAuthority, onCapture: @escaping (CapturedResponse) -> Void,
                onError: @escaping (String) -> Void) {
        self.authority = authority
        self.onCapture = onCapture
        self.onError = onError
    }

    public func start() async throws {
        guard listener == nil else { return }
        let slot = CaptureSlot(onCapture: onCapture)
        listener = try await ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 128)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelOption(ChannelOptions.socketOption(.tcp_nodelay), value: 1)
            .childChannelInitializer { [authority, connections, onError] channel in
                guard connections.channels.count < 256 else { return channel.close() }
                connections.add(channel)
                return channel.eventLoop.makeCompletedFuture {
                    try channel.pipeline.syncOperations.addHandlers([
                        IdleStateHandler(readTimeout: .seconds(120), writeTimeout: nil, allTimeout: nil),
                        IdleCloser(),
                        ProxyHandshake(authority: authority, connections: connections, slot: slot, onError: onError),
                    ])
                }
            }.bind(host: "127.0.0.1", port: Self.port).get()
    }

    public func stop() async {
        try? await listener?.close().get()
        listener = nil
        let loop = group.next()
        try? await loop.flatSubmit { [connections] in
            let channels = Array(connections.channels.values)
            return EventLoopFuture.andAllSucceed(channels.map { $0.close() }, on: loop)
        }.get()
    }

    deinit { group.shutdownGracefully { _ in } }
}
