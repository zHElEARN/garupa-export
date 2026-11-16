import Foundation
import GarupaKit
import Observation
import UIKit

/// Coordinates one in-memory setup/capture session. Platform runtime and UI state stay separate.
@MainActor
@Observable
final class AppModel {
    enum SessionPhase { case idle, preparing, certificate, startingProxy, active, stopping, failed }

    let certificates = CertificateModel()
    let catalog = CatalogModel()
    let capture = CaptureModel()
    let profiles: ProfileModel
    let notifications = CaptureNotifications.shared
    private(set) var phase = SessionPhase.idle
    private(set) var sessionError: String?
    private(set) var locationNeedsSettings = false
    private(set) var sessionID: UUID?
    private let background = LocationKeeper()
    private let networkAccess = NetworkAccessRequest()
    private var setupTask: Task<Void, Never>?
    private var monitorTask: Task<Void, Never>?
    private var reconciliation: Task<Void, Never>?
    private var forceRecheck = false

    init() {
        profiles = ProfileModel(catalog: catalog)
        capture.onChange = { [weak self] in self?.publishStage() }
        capture.onNetworkChange = { [weak self] in self?.recheck(forceProxyCheck: true) }
        profiles.onChange = { [weak self] in self?.publishStage() }
        background.onAuthorizationLost = { [weak self] in
            guard let self, self.sessionID != nil else { return }
            self.sessionError = "定位权限已关闭。"
            self.locationNeedsSettings = true
            self.capture.invalidateVerification()
            // A manually configured proxy may still point here. Keep its server alive.
            self.publishStage()
        }
        notifications.onOpen = { [weak self] _ in
            // A stale notification must not revive an old session or navigate to a stale step.
            self?.recheck(forceProxyCheck: true)
        }
    }

    var sessionActive: Bool { sessionID != nil }
    var busy: Bool { setupTask != nil || phase == .stopping }
    var canStart: Bool { !sessionActive && !busy && capture.phase == .stopped }

    var stage: GuideStage {
        if phase == .stopping { return .stopping }
        switch profiles.state {
        case .ready: return .complete
        case .failed: return .conversionFailed
        case .parsing: return .generating
        case .empty: break
        }
        if phase == .failed { return .startFailed }
        guard sessionActive else { return .welcome }
        if phase == .preparing { return .preparing }
        if !background.isActive { return .backgroundRequired }
        if certificates.trustedAuthority == nil { return .certificate }
        if phase == .startingProxy || capture.phase == .starting ||
            (phase == .certificate && capture.phase == .stopped) { return .startingProxy }
        if capture.progress != nil { return .receiving }
        if capture.proxyVerified { return .waitingForGame }
        if capture.verifying { return .verifyingProxy }
        return .configureProxy
    }

    func activate() async {
        locationNeedsSettings = background.authorizationDenied
        if sessionActive && !locationNeedsSettings && !background.isActive && !busy { resumeBackground() }
        await notifications.refreshPermission()
        await catalog.activate()
        if UIApplication.shared.applicationState == .active { recheck(forceProxyCheck: true) }
    }

    func setForeground(_ active: Bool) {
        notifications.appVisible = active
    }

    func startSession() {
        guard canStart else { return }
        let id = UUID()
        sessionID = id
        phase = .preparing
        sessionError = nil
        profiles.reset()
        capture.reset()
        notifications.beginSession(id)
        startMonitoring(id)
        setupTask = Task { [weak self] in
            guard let self else { return }
            await prepareSession(id)
            setupTask = nil
            if sessionID == id { recheck(forceProxyCheck: true) }
        }
    }

    private func prepareSession(_ id: UUID) async {
        do {
            networkAccess.start()
            await notifications.requestPermissionIfNeeded()
            try validateSession(id)
            try await background.start()
            try validateSession(id)
            try await catalog.ensureAvailable()
            try validateSession(id)
            phase = .certificate
            try await certificates.beginSession(id)
            try validateSession(id)
            publishStage()
        } catch {
            guard sessionID == id, !Task.isCancelled else { return }
            await failSetup(error, sessionID: id)
        }
    }

    private func startProxy(_ id: UUID) {
        guard setupTask == nil, let authority = certificates.trustedAuthority else { return }
        phase = .startingProxy
        publishStage()
        setupTask = Task { [weak self] in
            guard let self else { return }
            do {
                try validateSession(id)
                try await capture.start(authority: authority, sessionID: id) { [weak self] response in
                    guard let self, self.sessionID == id else { return }
                    self.profiles.parse(response)
                }
                try validateSession(id)
                phase = .active
                capture.checkProxy(force: true)
                publishStage()
            } catch {
                if sessionID == id, !Task.isCancelled { await failSetup(error, sessionID: id) }
            }
            setupTask = nil
        }
    }

    private func startMonitoring(_ id: UUID) {
        monitorTask?.cancel()
        monitorTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(2)) }
                catch { return }
                guard let self, self.sessionID == id else { return }
                self.recheck()
            }
        }
    }

    /// Polling covers certificate trust and Wi-Fi proxy edits that do not change the network path.
    func recheck(forceProxyCheck: Bool = false) {
        guard let id = sessionID, phase != .preparing, phase != .stopping else { return }
        forceRecheck = forceRecheck || forceProxyCheck
        guard reconciliation == nil else { return }
        reconciliation = Task { [weak self] in
            guard let self else { return }
            repeat {
                let force = forceRecheck
                forceRecheck = false
                await certificates.refreshTrust()
                guard !Task.isCancelled, sessionID == id else { break }
                if certificates.trustedAuthority == nil || !background.isActive {
                    capture.invalidateVerification()
                } else if capture.phase == .stopped, phase == .certificate {
                    startProxy(id)
                } else {
                    capture.checkProxy(force: force)
                }
                publishStage()
            } while forceRecheck && !Task.isCancelled && sessionID == id
            reconciliation = nil
        }
    }

    func resumeBackground() {
        guard let id = sessionID, !busy else { return }
        setupTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await background.start()
                try validateSession(id)
                sessionError = nil
                locationNeedsSettings = false
            } catch {
                if sessionID == id {
                    sessionError = error.localizedDescription
                    locationNeedsSettings = background.authorizationDenied
                }
            }
            setupTask = nil
            if sessionID == id {
                publishStage()
                recheck(forceProxyCheck: true)
            }
        }
    }

    func stopSession() async {
        guard phase != .stopping else { return }
        let preparing = phase == .preparing
        sessionID = nil
        phase = .stopping
        if preparing && catalog.cache == nil { catalog.cancelRefresh() }
        let pending = setupTask
        pending?.cancel()
        monitorTask?.cancel()
        monitorTask = nil
        let checking = reconciliation
        checking?.cancel()
        background.stop()
        await pending?.value
        await checking?.value
        background.stop()
        setupTask = nil
        reconciliation = nil
        forceRecheck = false
        await capture.stop()
        await certificates.endSession()
        notifications.endSession()
        sessionError = nil
        phase = .idle
    }

    private func failSetup(_ error: Error, sessionID id: UUID) async {
        monitorTask?.cancel()
        monitorTask = nil
        background.stop()
        await capture.stop()
        await certificates.endSession()
        guard sessionID == id else { return }
        sessionError = error.localizedDescription
        locationNeedsSettings = background.authorizationDenied
        phase = .failed
        publishStage()
        sessionID = nil
    }

    private func validateSession(_ id: UUID) throws {
        try Task.checkCancellation()
        guard sessionID == id else { throw CancellationError() }
    }

    private func publishStage() {
        notifications.transition(to: stage, sessionID: sessionID)
    }
}
