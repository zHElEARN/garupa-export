import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class AppModel {
    let certificates = CertificateModel()
    let catalog = CatalogModel()
    let capture = CaptureModel()
    let profiles: ProfileModel
    private let networkAccess = NetworkAccessRequest()

    init() {
        profiles = ProfileModel(catalog: catalog)
    }

    func activate() async {
        guard UIApplication.shared.applicationState == .active else { return }
        // Request wireless-data access before certificate setup or switching to the game.
        networkAccess.start()
        async let certificateSetup: () = certificates.activate()
        await catalog.activate()
        await certificateSetup
    }

    func startCapture() async {
        guard capture.phase == .stopped, let authority = certificates.trustedAuthority,
              catalog.cache != nil else { return }
        profiles.reset()
        await capture.start(authority: authority) { [weak profiles] response in
            profiles?.parse(response)
        }
    }
}
