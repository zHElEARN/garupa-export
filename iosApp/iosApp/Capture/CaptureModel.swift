import Foundation
import GarupaKit
import Observation

@MainActor
@Observable
final class CaptureModel {
    enum Phase { case stopped, starting, running, stopping }

    private(set) var phase = Phase.stopped
    private(set) var captured = false
    private(set) var error: String?

    private let location = LocationKeeper()
    private var proxy: LocalProxy?
    private var sessionID: UUID?

    init() {
        location.onAuthorizationLost = { [weak self] in
            guard let self else { return }
            self.error = "定位权限已撤销，后台代理已停止"
            Task { await self.stop() }
        }
    }

    var status: String {
        switch phase {
        case .stopped: return "未运行"
        case .starting: return "正在启动"
        case .stopping: return "正在停止"
        case .running: return captured ? "已获取存档 · 代理运行中" : "运行中 · 等待存档"
        }
    }

    func start(authority: CertificateAuthority, onCaptured: @escaping @MainActor (CapturedResponse) -> Void) async {
        guard phase == .stopped else { return }
        phase = .starting
        error = nil
        captured = false
        let id = UUID()
        sessionID = id
        do {
            try await location.start()
            let server = LocalProxy(authority: authority, onCapture: { [weak self] response in
                Task { @MainActor in
                    guard let self, self.sessionID == id else { return }
                    self.captured = true
                    self.error = nil
                    onCaptured(response)
                }
            }, onError: { [weak self] message in
                Task { @MainActor in
                    guard let self, self.sessionID == id, self.phase == .running,
                          !self.captured else { return }
                    self.error = message
                }
            })
            proxy = server
            try await server.start()
            guard location.isActive else { throw GarupaError.message("定位已停止，无法维持后台代理") }
            phase = .running
        } catch {
            await shutdown()
            self.error = error.localizedDescription
        }
    }

    func stop() async {
        guard phase == .running else { return }
        phase = .stopping
        await shutdown()
    }

    private func shutdown() async {
        await proxy?.stop()
        proxy = nil
        location.stop()
        phase = .stopped
    }
}
