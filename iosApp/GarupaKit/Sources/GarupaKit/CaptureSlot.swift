import Foundation

/// A session captures one complete response. State is confined to the proxy's event loop.
final class CaptureSlot: @unchecked Sendable {
    private enum State { case waiting, receiving, complete }
    private var state = State.waiting
    private let onCapture: (CapturedResponse) -> Void
    private let onProgress: (CaptureProgress?) -> Void
    private var totalBytes: Int?
    private var reportedAt: TimeInterval = 0

    init(onCapture: @escaping (CapturedResponse) -> Void, onProgress: @escaping (CaptureProgress?) -> Void) {
        self.onCapture = onCapture
        self.onProgress = onProgress
    }

    func claim(totalBytes: Int?) -> Bool {
        guard state == .waiting else { return false }
        state = .receiving
        self.totalBytes = totalBytes.flatMap { $0 > 0 ? $0 : nil }
        reportedAt = ProcessInfo.processInfo.systemUptime
        onProgress(CaptureProgress(receivedBytes: 0, totalBytes: self.totalBytes))
        return true
    }

    func received(_ count: Int) {
        guard state == .receiving else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard now - reportedAt >= 0.2 else { return }
        reportedAt = now
        onProgress(CaptureProgress(receivedBytes: count, totalBytes: totalBytes))
    }

    func abort() {
        guard state == .receiving else { return }
        state = .waiting
        onProgress(nil)
    }

    func finish(_ response: CapturedResponse) {
        state = .complete
        onCapture(response)
    }
}
