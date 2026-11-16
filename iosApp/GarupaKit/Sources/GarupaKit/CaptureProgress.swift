import Foundation

public struct CaptureProgress: Sendable {
    public let receivedBytes: Int
    public let totalBytes: Int?

    public init(receivedBytes: Int, totalBytes: Int?) {
        self.receivedBytes = receivedBytes
        self.totalBytes = totalBytes
    }
}
