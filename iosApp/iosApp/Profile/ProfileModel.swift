import Foundation
import GarupaKit
import Observation

/// Owns conversion, its result, and retrying the latest response after a data refresh.
@MainActor
@Observable
final class ProfileModel {
    enum State {
        case empty, parsing
        case ready(ProfileDocument)
        case failed(String)
    }

    private(set) var state = State.empty
    private let catalog: CatalogModel
    private var latestCapture: CapturedResponse?
    private var parseTask: Task<Void, Never>?

    init(catalog: CatalogModel) {
        self.catalog = catalog
    }

    func reset() {
        parseTask?.cancel()
        parseTask = nil
        latestCapture = nil
        state = .empty
    }

    func parse(_ response: CapturedResponse) {
        parseTask?.cancel()
        latestCapture = response
        state = .parsing
        let data = catalog.cache?.catalog
        parseTask = Task { [weak self] in
            do {
                guard let data else { throw GarupaError.message("请先下载 Bestdori 卡牌数据") }
                let document = try await Task.detached(priority: .userInitiated) {
                    try ProfileExporter.export(response, catalog: data)
                }.value
                try Task.checkCancellation()
                self?.state = .ready(document)
            } catch {
                guard !Task.isCancelled else { return }
                self?.state = .failed(error.localizedDescription)
            }
        }
    }

    func retry() {
        guard let latestCapture else { return }
        if case .parsing = state { return }
        parse(latestCapture)
    }
}
