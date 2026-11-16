import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class AppModel {
    let certificates = CertificateModel()
    let capture = CaptureModel()
    private let networkAccess = NetworkAccessRequest()

    func activate() async {
        guard UIApplication.shared.applicationState == .active else { return }
        // Request wireless-data access before certificate setup or switching to the game.
        networkAccess.start()
        await certificates.activate()
    }

    func startCapture() async {
        guard let authority = certificates.trustedAuthority else { return }
        await capture.start(authority: authority)
    }
}
