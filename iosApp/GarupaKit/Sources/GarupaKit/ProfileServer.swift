import Foundation
import NIOCore
import NIOPosix
import NIOHTTP1

/// Safari downloads the public CA profile from this loopback-only HTTP endpoint.
public final class ProfileServer {
    public static let url = URL(string: "http://127.0.0.1:8877/garupa.mobileconfig")!
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private var listener: Channel?

    public init() {}

    public func start(profile: Data) async throws {
        guard listener == nil else { return }
        listener = try await ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelInitializer { channel in
                channel.pipeline.configureHTTPServerPipeline(withErrorHandling: true).flatMap {
                    channel.pipeline.addHandler(ProfileHandler(profile: profile))
                }
            }.bind(host: "127.0.0.1", port: 8877).get()
    }

    deinit {
        listener?.close(promise: nil)
        group.shutdownGracefully { _ in }
    }
}

private final class ProfileHandler: ChannelInboundHandler {
    typealias InboundIn = HTTPServerRequestPart
    typealias OutboundOut = HTTPServerResponsePart
    let profile: Data
    var request: HTTPRequestHead?
    init(profile: Data) { self.profile = profile }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        switch unwrapInboundIn(data) {
        case .head(let head): request = head
        case .body: break
        case .end:
            let valid = request?.method == .GET && request?.uri == "/garupa.mobileconfig"
            let data = valid ? profile : Data()
            let headers = HTTPHeaders([
                ("Content-Type", "application/x-apple-aspen-config"),
                ("Content-Disposition", "attachment; filename=garupa.mobileconfig"),
                ("Content-Length", String(data.count)), ("Connection", "close"), ("Cache-Control", "no-store"),
            ])
            context.write(wrapOutboundOut(.head(.init(version: .http1_1, status: valid ? .ok : .notFound, headers: headers))), promise: nil)
            var body = context.channel.allocator.buffer(capacity: data.count)
            body.writeBytes(data)
            context.write(wrapOutboundOut(.body(.byteBuffer(body))), promise: nil)
            context.writeAndFlush(wrapOutboundOut(.end(nil))).whenComplete { _ in context.close(promise: nil) }
        }
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) { context.close(promise: nil) }
}
