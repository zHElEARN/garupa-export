import Foundation
import GarupaKit
import Observation
import UIKit

@MainActor
@Observable
final class CertificateModel {
    enum State: Equatable {
        case preparing, checking, trusted, untrusted
        case failed(String)

        var title: String {
            switch self {
            case .preparing: return "正在准备"
            case .checking: return "正在检测"
            case .trusted: return "系统信任验证通过"
            case .untrusted: return "尚未通过系统信任验证"
            case .failed: return "检测失败"
            }
        }
    }

    private(set) var state = State.preparing
    private(set) var profileProvided = false
    private(set) var installationError: String?
    private var authority: CertificateAuthority?
    private var profileServer: ProfileServer?
    private var preparation: Task<CertificateAuthority, Error>?
    private var trustCheck: Task<Void, Never>?
    private var sessionID: UUID?

    var canInstall: Bool { authority != nil && profileServer != nil }
    var trustedAuthority: CertificateAuthority? { state == .trusted ? authority : nil }

    func beginSession(_ id: UUID) async throws {
        sessionID = id
        profileProvided = false
        installationError = nil
        let ca = try await prepareAuthority()
        try Task.checkCancellation()
        guard sessionID == id else { throw CancellationError() }
        let server = ProfileServer()
        profileServer = server
        try await server.start(profile: ca.configurationProfile()) { [weak self] in
            Task { @MainActor in
                guard let self, self.sessionID == id else { return }
                // A completed HTTP response only proves that the profile was provided.
                self.profileProvided = true
            }
        }
        await refreshTrust()
    }

    private func prepareAuthority() async throws -> CertificateAuthority {
        if let authority { return authority }
        if let preparation { return try await preparation.value }
        state = .preparing
        let task = Task.detached(priority: .userInitiated) { try CertificateAuthority() }
        preparation = task
        do {
            let ca = try await task.value
            authority = ca
            preparation = nil
            return ca
        } catch {
            preparation = nil
            state = .failed(error.localizedDescription)
            throw error
        }
    }

    func refreshTrust() async {
        guard let authority else { return }
        if let trustCheck { await trustCheck.value; return }
        let task = Task {
            // Preserve the last result while rechecking to avoid flickering between steps.
            if state == .preparing { state = .checking }
            do {
                let trusted = try await Task.detached(priority: .userInitiated) { try authority.isTrusted() }.value
                state = trusted ? .trusted : .untrusted
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
        trustCheck = task
        await task.value
        trustCheck = nil
    }

    func installProfile() {
        guard canInstall else { return }
        installationError = nil
        let id = sessionID
        UIApplication.shared.open(ProfileServer.url) { [weak self] opened in
            Task { @MainActor in
                guard let self, self.sessionID == id, !opened else { return }
                self.installationError = "无法打开描述文件下载地址，请稍后重试。"
            }
        }
    }

    func endSession() async {
        sessionID = nil
        await profileServer?.stop()
        profileServer = nil
    }
}
