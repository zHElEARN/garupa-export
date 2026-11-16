import Foundation

/// A session captures one complete response. State is confined to the proxy's event loop.
final class CaptureSlot: @unchecked Sendable {
    private enum State { case waiting, receiving, complete }
    private var state = State.waiting
    private let onCapture: (CapturedResponse) -> Void

    init(onCapture: @escaping (CapturedResponse) -> Void) { self.onCapture = onCapture }

    func claim() -> Bool {
        guard state == .waiting else { return false }
        state = .receiving
        return true
    }

    func abort() {
        guard state == .receiving else { return }
        state = .waiting
    }

    func finish(_ response: CapturedResponse) {
        state = .complete
        onCapture(response)
    }
}
