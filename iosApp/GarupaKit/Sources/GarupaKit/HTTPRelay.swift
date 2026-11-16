import Foundation
import NIOCore
import NIOHTTP1

final class HTTPPair {
    struct Request { let capture: Bool }
    var requests: [Request] = []
    let capture: CaptureSlot?
    let onError: (String) -> Void
    init(capture: CaptureSlot?, onError: @escaping (String) -> Void) { self.capture = capture; self.onError = onError }
}

final class RequestForwarder: ChannelInboundHandler {
    typealias InboundIn = HTTPServerRequestPart
    let remote: Channel
    let pair: HTTPPair
    let plainHost: String?
    let port: Int
    init(remote: Channel, pair: HTTPPair, plainHost: String?, port: Int) {
        self.remote = remote; self.pair = pair; self.plainHost = plainHost; self.port = port
    }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let part: HTTPClientRequestPart
        switch unwrapInboundIn(data) {
        case .head(var head):
            guard pair.requests.count < 32 else { context.close(promise: nil); return }
            if let plainHost {
                guard let url = URL(string: head.uri), url.scheme == "http", url.host?.lowercased() == plainHost,
                      (url.port ?? 80) == port, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
                    context.close(promise: nil); return
                }
                head.uri = (components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath)
                    + (components.percentEncodedQuery.map { "?\($0)" } ?? "")
            }
            let path = head.uri.components(separatedBy: "?")[0]
            pair.requests.append(.init(capture: head.method == .GET && path.range(of: #"^/api/suite/user/[0-9]+$"#, options: .regularExpression) != nil))
            head.headers.remove(name: "proxy-connection")
            head.headers.remove(name: "proxy-authorization")
            part = .head(head)
        case .body(let body): part = .body(.byteBuffer(body))
        case .end(let trailers): part = .end(trailers)
        }
        remote.writeAndFlush(part, promise: nil)
    }
    func channelWritabilityChanged(context: ChannelHandlerContext) {
        _ = remote.setOption(ChannelOptions.autoRead, value: context.channel.isWritable)
    }
    func channelInactive(context: ChannelHandlerContext) { remote.close(promise: nil) }
    func errorCaught(context: ChannelHandlerContext, error: Error) {
        if pair.capture != nil { pair.onError("游戏请求连接失败：\(error.localizedDescription)") }
        context.close(promise: nil); remote.close(promise: nil)
    }
}

final class ResponseForwarder: ChannelInboundHandler {
    typealias InboundIn = HTTPClientResponsePart
    let client: Channel
    let pair: HTTPPair
    var informational = false
    var body: Data?
    var encoding: String?
    private var lastWrite: EventLoopFuture<Void>?
    init(client: Channel, pair: HTTPPair) { self.client = client; self.pair = pair }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let part: HTTPServerResponsePart
        switch unwrapInboundIn(data) {
        case .head(let head):
            guard !pair.requests.isEmpty, head.status.code != 101 else { context.close(promise: nil); return }
            informational = (100..<200).contains(head.status.code)
            let total = head.headers.contains(name: "transfer-encoding") ? nil :
                head.headers.first(name: "content-length").flatMap(Int.init)
            if !informational, head.status == .ok, pair.requests[0].capture,
               pair.capture?.claim(totalBytes: total) == true {
                body = Data()
                encoding = head.headers["content-encoding"].joined(separator: ",")
            }
            part = .head(head)
        case .body(let bytes):
            if let count = body?.count {
                if count + bytes.readableBytes > CapturedResponse.maximumBytes {
                    body = nil
                    pair.capture?.abort()
                    pair.onError("存档响应超过大小限制")
                } else {
                    body?.append(contentsOf: bytes.readableBytesView)
                    pair.capture?.received(count + bytes.readableBytes)
                }
            }
            part = .body(.byteBuffer(bytes))
        case .end(let trailers):
            if !informational, !pair.requests.isEmpty { pair.requests.removeFirst() }
            let captured = body.flatMap { $0.isEmpty ? nil : CapturedResponse(body: $0, contentEncoding: encoding) }
            let ownedCapture = body != nil
            body = nil
            let flushed = client.writeAndFlush(HTTPServerResponsePart.end(trailers))
            lastWrite = flushed
            flushed.whenComplete { result in
                if case .success = result, let captured, let slot = self.pair.capture {
                    slot.finish(captured)
                } else if ownedCapture {
                    self.pair.capture?.abort()
                }
            }
            return
        }
        lastWrite = client.writeAndFlush(part)
    }
    func channelWritabilityChanged(context: ChannelHandlerContext) {
        _ = client.setOption(ChannelOptions.autoRead, value: context.channel.isWritable)
    }
    func channelInactive(context: ChannelHandlerContext) {
        if body != nil { pair.capture?.abort() }
        // An upstream EOF must not discard the response still queued for the game.
        if let lastWrite { lastWrite.whenComplete { _ in self.client.close(promise: nil) } }
        else { client.close(promise: nil) }
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) {
        if pair.capture != nil { pair.onError("游戏响应连接失败：\(error.localizedDescription)") }
        context.close(promise: nil); client.close(promise: nil)
    }
}
