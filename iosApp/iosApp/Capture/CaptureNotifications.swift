import Foundation
import Observation
import UIKit
import UserNotifications

/// The delegate is installed during app launch; notification links always show the current in-memory stage.
@MainActor
@Observable
final class CaptureNotifications: NSObject, UNUserNotificationCenterDelegate {
    private enum SetupProgress { case certificateTrusted, proxyVerified }

    static let shared = CaptureNotifications()
    private static let identifier = "garupa.capture.milestone"
    private let center = UNUserNotificationCenter.current()
    private(set) var enabled = true
    private(set) var focusRequest = 0
    var onOpen: ((UUID?) -> Void)?
    var appVisible = true
    private var lastStage: GuideStage?
    private var setupProgress: SetupProgress?
    private var sessionID: UUID?
    private var delivery: Task<Void, Never>?

    func installDelegate() { center.delegate = self }

    func refreshPermission() async {
        let settings = await center.notificationSettings()
        enabled = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) &&
            settings.alertSetting == .enabled
    }

    func requestPermissionIfNeeded() async {
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
        }
        await refreshPermission()
    }

    func beginSession(_ id: UUID) {
        delivery?.cancel()
        sessionID = id
        lastStage = nil
        setupProgress = nil
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        center.removeDeliveredNotifications(withIdentifiers: [Self.identifier])
    }

    func transition(to stage: GuideStage, certificateTrusted: Bool, proxyVerified: Bool, sessionID id: UUID?) {
        guard let id, sessionID == id else { return }
        let setupMessage = setupNotification(for: stage, certificateTrusted: certificateTrusted, proxyVerified: proxyVerified)
        guard lastStage != stage || setupMessage != nil else { return }
        lastStage = stage
        delivery?.cancel()
        guard let message = setupMessage ?? stage.milestoneNotification, enabled, !appVisible else { return }
        delivery = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(250)) }
            catch { return }
            guard let self, !Task.isCancelled, sessionID == id, lastStage == stage, !appVisible else { return }
            let content = UNMutableNotificationContent()
            content.title = message.title
            content.body = message.body
            content.sound = .default
            content.threadIdentifier = id.uuidString
            content.userInfo = ["sessionID": id.uuidString, "stage": stage.rawValue]
            try? await center.add(UNNotificationRequest(identifier: Self.identifier, content: content, trigger: nil))
        }
    }

    private func setupNotification(for stage: GuideStage, certificateTrusted: Bool,
                                   proxyVerified: Bool) -> (title: String, body: String)? {
        let body = "请返回 Garupa Export 查看下一步。"
        if proxyVerified, setupProgress != .proxyVerified {
            setupProgress = .proxyVerified
            if stage == .waitingForGame { return ("代理设置已完成", body) }
        } else if certificateTrusted, stage == .configureProxy, setupProgress == nil {
            setupProgress = .certificateTrusted
            return ("证书已受信任", body)
        }
        return nil
    }

    func endSession() {
        delivery?.cancel()
        sessionID = nil
        lastStage = nil
        setupProgress = nil
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        center.removeDeliveredNotifications(withIdentifiers: [Self.identifier])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                           willPresent notification: UNNotification,
                                           withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // The native guide already presents foreground progress and errors.
        completionHandler([])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                           didReceive response: UNNotificationResponse,
                                           withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = (response.notification.request.content.userInfo["sessionID"] as? String).flatMap(UUID.init(uuidString:))
        Task { @MainActor [weak self] in
            guard let self else { return }
            focusRequest += 1
            onOpen?(id)
        }
        completionHandler()
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        CaptureNotifications.shared.installDelegate()
        return true
    }
}
