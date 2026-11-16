import Foundation

/// An endpoint answered only by this proxy instance, without DNS or an upstream connection.
public struct ProxyProbe: Sendable {
    public static let host = "garupa-export.invalid"
    private let path = "/proxy-check/\(UUID().uuidString)"
    public let response = UUID().uuidString

    public init() {}

    public func requestURL() -> URL {
        URL(string: "http://\(Self.host)\(path)?nonce=\(UUID().uuidString)")!
    }

    func matches(method: String, url: URL) -> Bool {
        method == "GET" && url.scheme == "http" && url.host == Self.host &&
            url.path == path && url.port == nil && url.user == nil && url.password == nil
    }
}
