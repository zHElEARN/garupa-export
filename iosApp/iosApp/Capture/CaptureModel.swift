import Foundation
import GarupaKit
import Observation

@MainActor
@Observable
final class CaptureModel {
    enum Phase { case stopped, starting, running, stopping }
    enum ExportState {
        case empty, parsing
        case ready(ProfileDocument)
        case failed(String)

        var hasCapture: Bool {
            if case .empty = self { return false }
            return true
        }
    }

    private(set) var phase = Phase.stopped
    private(set) var exportState = ExportState.empty
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
        case .running: return exportState.hasCapture ? "已获取存档 · 代理运行中" : "运行中 · 等待存档"
        }
    }

    func start(authority: CertificateAuthority) async {
        guard phase == .stopped else { return }
        phase = .starting
        error = nil
        exportState = .empty
        let id = UUID()
        sessionID = id
        do {
            try await location.start()
            let server = LocalProxy(authority: authority, onCapture: { [weak self] response in
                Task { @MainActor in await self?.receive(response, session: id) }
            }, onError: { [weak self] message in
                Task { @MainActor in
                    guard let self, self.sessionID == id, self.phase == .running,
                          !self.exportState.hasCapture else { return }
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

    private func receive(_ response: CapturedResponse, session: UUID) async {
        guard sessionID == session else { return }
        error = nil
        exportState = .parsing
        do {
            let document = try await Task.detached(priority: .userInitiated) { try ProfileExporter.export(response) }.value
            guard sessionID == session else { return }
            exportState = .ready(document)
        } catch {
            guard sessionID == session else { return }
            exportState = .failed(error.localizedDescription)
        }
    }
}
