import CoreLocation
import Foundation
import GarupaKit

@MainActor
// The manager is created on the main actor, so its delegate uses the main run loop.
final class LocationKeeper: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var permission: CheckedContinuation<Void, Error>?
    private(set) var isActive = false
    var onAuthorizationLost: (() -> Void)?

    override init() {
        super.init()
        manager.delegate = self
    }

    func start() async throws {
        guard !isActive else { return }
        if manager.authorizationStatus == .notDetermined {
            try await withCheckedThrowingContinuation { continuation in
                permission = continuation
                manager.requestWhenInUseAuthorization()
            }
        }
        try Task.checkCancellation()
        guard [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) else {
            throw GarupaError.message("未获得定位权限，无法维持后台代理")
        }
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        manager.activityType = .other
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.startUpdatingLocation()
        isActive = true
    }

    func stop() {
        permission?.resume(throwing: CancellationError())
        permission = nil
        isActive = false
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined else { return }
        let allowed = [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
        if let pending = permission {
            permission = nil
            if allowed { pending.resume() }
            else { pending.resume(throwing: GarupaError.message("未获得定位权限")) }
        }
        if isActive && !allowed { stop(); onAuthorizationLost?() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // Location updates provide background runtime; coordinates are not retained.
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .denied, isActive { stop(); onAuthorizationLost?() }
    }
}
