import Foundation

public struct CapturedResponse: Sendable {
    public static let maximumBytes = 32 * 1024 * 1024
    public let body: Data
    public let contentEncoding: String?
}
