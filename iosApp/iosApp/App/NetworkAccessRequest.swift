import Network
import Dispatch

/// A real foreground connection triggers iOS wireless-data authorization when needed.
/// This requests access; it does not infer authorization from reachability.
@MainActor
final class NetworkAccessRequest {
    private var connection: NWConnection?

    func start() {
        guard connection == nil else { return }
        let parameters = NWParameters.tcp
        parameters.preferNoProxies = true
        let connection = NWConnection(host: "bestdori.com", port: .https, using: parameters)
        self.connection = connection
        connection.stateUpdateHandler = { [weak connection] state in
            switch state {
            case .ready, .failed:
                connection?.cancel()
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    deinit { connection?.cancel() }
}
