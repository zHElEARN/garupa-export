import Foundation
import GarupaKit
import Observation
import UIKit

@MainActor
@Observable
final class CertificateModel {
    enum State {
        case preparing, checking, trusted, untrusted
        case failed(String)

        var title: String {
            switch self {
            case .preparing: return "正在准备"
            case .checking: return "正在检测"
            case .trusted: return "已安装并完全信任"
            case .untrusted: return "未安装或未完全信任"
            case .failed: return "检测失败"
            }
        }
    }

    private(set) var state = State.preparing
    private(set) var installationError: String?
    private var authority: CertificateAuthority?
    private let profileServer = ProfileServer()
    private var preparation: Task<CertificateAuthority, Error>?
    private var activation: Task<Void, Never>?
    private var installationTask: UIBackgroundTaskIdentifier = .invalid

    var canInstall: Bool { authority != nil }
    var trustedAuthority: CertificateAuthority? {
        guard case .trusted = state else { return nil }
        return authority
    }

    func activate() async {
        endInstallationTask()
        if let activation { await activation.value; return }
        let task = Task { await refresh() }
        activation = task
        await task.value
        activation = nil
    }

    private func prepare() async throws -> CertificateAuthority {
        if let preparation { return try await preparation.value }
        let task = Task {
            let ca = try await Task.detached(priority: .userInitiated) { try CertificateAuthority() }.value
            try await profileServer.start(profile: ca.configurationProfile())
            return ca
        }
        preparation = task
        return try await task.value
    }

    private func refresh() async {
        do {
            let ca = try await prepare()
            authority = ca
            state = .checking
            let trusted = try await Task.detached(priority: .userInitiated) { try ca.isTrusted() }.value
            state = trusted ? .trusted : .untrusted
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func installProfile() {
        guard canInstall else { return }
        installationError = nil
        endInstallationTask()
        // Safari must be able to finish downloading from the app's loopback listener.
        installationTask = UIApplication.shared.beginBackgroundTask(withName: "CA profile download") { [weak self] in
            Task { @MainActor in self?.endInstallationTask() }
        }
        UIApplication.shared.open(ProfileServer.url) { [weak self] opened in
            Task { @MainActor in
                guard let self, !opened else { return }
                self.endInstallationTask()
                self.installationError = "无法打开描述文件下载地址"
            }
        }
    }

    private func endInstallationTask() {
        guard installationTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(installationTask)
        installationTask = .invalid
    }
}
