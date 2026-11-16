import Foundation
import GarupaKit
import Observation

/// Proxy runtime only; the surrounding setup/background session is owned by AppModel.
@MainActor
@Observable
final class CaptureModel {
    enum Phase { case stopped, starting, running, stopping }

    private(set) var phase = Phase.stopped
    private(set) var captured = false
    private(set) var error: String?
    private(set) var progress: CaptureProgress?
    private(set) var proxyVerified = false
    private(set) var verifying = false
    private(set) var verificationError: String?
    private(set) var environment: ProxyEnvironment?
    var onChange: (() -> Void)?
    var onNetworkChange: (() -> Void)?

    private let verifier = SystemProxyVerifier()
    private var proxy: LocalProxy?
    private var probe: ProxyProbe?
    private var sessionID: UUID?
    private var verificationTask: Task<Void, Never>?
    private var verificationID: UUID?

    init() {
        verifier.onNetworkChange = { [weak self] in self?.onNetworkChange?() }
    }

    var status: String {
        switch phase {
        case .stopped: return "未运行"
        case .starting: return "正在启动"
        case .stopping: return "正在停止"
        case .running: return captured ? "已获取存档 · 代理运行中" : "运行中 · 等待存档"
        }
    }

    func reset() {
        guard phase == .stopped else { return }
        sessionID = nil
        captured = false
        error = nil
        progress = nil
        proxyVerified = false
        verificationError = nil
        environment = nil
    }

    func start(authority: CertificateAuthority, sessionID id: UUID,
               onCaptured: @escaping @MainActor (CapturedResponse) -> Void) async throws {
        guard phase == .stopped else { return }
        phase = .starting
        sessionID = id
        let probe = ProxyProbe()
        self.probe = probe
        onChange?()
        let server = LocalProxy(authority: authority, onCapture: { [weak self] response in
            Task { @MainActor in
                guard let self, self.sessionID == id else { return }
                self.captured = true
                self.error = nil
                onCaptured(response)
                self.onChange?()
            }
        }, onError: { [weak self] message in
            Task { @MainActor in
                guard let self, self.sessionID == id, !self.captured else { return }
                self.error = message
                self.onChange?()
            }
        }, probe: probe, onProgress: { [weak self] progress in
            Task { @MainActor in
                guard let self, self.sessionID == id, !self.captured else { return }
                self.progress = progress
                self.onChange?()
            }
        })
        proxy = server
        do {
            try await server.start()
            try Task.checkCancellation()
            guard sessionID == id else { throw CancellationError() }
            phase = .running
            onChange?()
        } catch {
            sessionID = nil
            await server.stop()
            proxy = nil
            self.probe = nil
            phase = .stopped
            throw error
        }
    }

    func checkProxy(force: Bool = false) {
        guard phase == .running, let probe else { return }
        let current = verifier.environment(for: probe)
        if !force, environment == current { return }
        verificationTask?.cancel()
        let id = UUID()
        verificationID = id
        let preserveVerified = proxyVerified && environment == current
        environment = current
        if let error = current.configurationError {
            proxyVerified = false
            verifying = false
            verificationError = error
            onChange?()
            return
        }
        proxyVerified = preserveVerified
        verifying = true
        verificationError = nil
        onChange?()
        verificationTask = Task { [weak self] in
            guard let self else { return }
            let verified: Bool
            do { verified = try await verifier.verify(probe) }
            catch {
                guard !Task.isCancelled else { return }
                verified = false
            }
            guard !Task.isCancelled, verificationID == id, phase == .running else { return }
            verifying = false
            guard verifier.environment(for: probe) == current else {
                environment = nil
                checkProxy()
                return
            }
            proxyVerified = verified
            verificationError = verified ? nil : "设置已检测到，但请求尚未通过本地代理。请确认服务器和端口后重新检测。"
            onChange?()
        }
    }

    func invalidateVerification() {
        verificationTask?.cancel()
        verificationID = nil
        verifying = false
        proxyVerified = false
        environment = nil
    }

    func stop() async {
        guard phase != .stopped else { return }
        phase = .stopping
        sessionID = nil
        invalidateVerification()
        onChange?()
        await proxy?.stop()
        proxy = nil
        probe = nil
        progress = nil
        phase = .stopped
        onChange?()
    }
}
