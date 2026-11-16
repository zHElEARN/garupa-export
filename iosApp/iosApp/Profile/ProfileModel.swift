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
    var onChange: (() -> Void)?
    private let catalog: CatalogModel
    private var latestCapture: CapturedResponse?
    private var parseTask: Task<Void, Never>?

    init(catalog: CatalogModel) {
        self.catalog = catalog
        try? FileManager.default.removeItem(at: ProfileExporter.temporaryDirectory)
    }

    func reset() {
        parseTask?.cancel()
        parseTask = nil
        latestCapture = nil
        if case .ready(let document) = state { try? FileManager.default.removeItem(at: document.url) }
        state = .empty
        onChange?()
    }

    func parse(_ response: CapturedResponse) {
        parseTask?.cancel()
        latestCapture = response
        state = .parsing
        onChange?()
        let data = catalog.cache?.catalog
        parseTask = Task { [weak self] in
            do {
                guard let data else { throw GarupaError.message("请先下载 Bestdori 卡牌数据") }
                let document = try await Task.detached(priority: .userInitiated) {
                    try ProfileExporter.export(response, catalog: data)
                }.value
                if Task.isCancelled {
                    try? FileManager.default.removeItem(at: document.url)
                    return
                }
                self?.state = .ready(document)
                self?.onChange?()
            } catch {
                guard !Task.isCancelled else { return }
                self?.state = .failed(error.localizedDescription)
                self?.onChange?()
            }
        }
    }

    func retry() {
        guard let latestCapture else { return }
        if case .parsing = state { return }
        parse(latestCapture)
    }
}
