import CFNetwork
import Foundation
import GarupaKit
import Network

struct ProxyEnvironment: Equatable {
    enum Route { case local, direct, automatic, other, unavailable }
    let route: Route
    let settings: NSDictionary
    let networkRevision: Int
    let connected: Bool
    let wifi: Bool

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.route == rhs.route && lhs.settings.isEqual(rhs.settings) &&
            lhs.networkRevision == rhs.networkRevision && lhs.connected == rhs.connected && lhs.wifi == rhs.wifi
    }

    var configurationError: String? {
        guard connected else { return "尚未连接网络，请连接 Wi-Fi 后重试。" }
        switch route {
        case .local: return nil
        case .direct: return "尚未检测到本地代理。请保存服务器 127.0.0.1、端口 8888 后返回本应用。"
        case .automatic: return "当前使用自动代理配置，请将当前 Wi-Fi 的 HTTP 代理改为“手动”。"
        case .other: return "当前代理与本应用不一致，请检查服务器、端口和代理排除设置。"
        case .unavailable: return "暂时无法读取系统代理设置，请稍后重新检测。"
        }
    }
}

/// Observes network changes and reads the effective route for the session's diagnostic URL.
@MainActor
final class SystemProxyVerifier {
    var onNetworkChange: (() -> Void)?
    private let monitor = NWPathMonitor()
    private var revision = 0

    init() {
        monitor.pathUpdateHandler = { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.revision += 1
                self.onNetworkChange?()
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.zhelearn.garupaexport.proxy-path"))
    }

    func environment(for probe: ProxyProbe) -> ProxyEnvironment {
        let path = monitor.currentPath
        let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue()
        let proxies = settings.flatMap {
            CFNetworkCopyProxiesForURL(probe.requestURL() as CFURL, $0).takeRetainedValue() as? [[String: Any]]
        }
        let route: ProxyEnvironment.Route
        if let proxy = proxies?.first, let type = proxy[kCFProxyTypeKey as String] as? String {
            if type == (kCFProxyTypeHTTP as String) {
                let host = (proxy[kCFProxyHostNameKey as String] as? String)?.lowercased()
                let port = (proxy[kCFProxyPortNumberKey as String] as? NSNumber)?.intValue
                route = ["127.0.0.1", "localhost"].contains(host ?? "") && port == LocalProxy.port ? .local : .other
            } else if type == (kCFProxyTypeNone as String) {
                route = .direct
            } else if type == (kCFProxyTypeAutoConfigurationURL as String) ||
                        type == (kCFProxyTypeAutoConfigurationJavaScript as String) {
                route = .automatic
            } else {
                route = .other
            }
        } else {
            route = .unavailable
        }
        return ProxyEnvironment(route: route, settings: settings.map { $0 as NSDictionary } ?? NSDictionary(),
                                networkRevision: revision, connected: path.status == .satisfied,
                                wifi: path.usesInterfaceType(.wifi))
    }

    func verify(_ probe: ProxyProbe) async throws -> Bool {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 6
        config.urlCache = nil
        config.httpShouldSetCookies = false
        // Leave connectionProxyDictionary unset: this request must follow system settings.
        let session = URLSession(configuration: config, delegate: ProbeSessionDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: probe.requestURL(), cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        let (data, response) = try await session.data(for: request)
        return (response as? HTTPURLResponse)?.statusCode == 200 && data == Data(probe.response.utf8)
    }

    deinit { monitor.cancel() }
}

private final class ProbeSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
