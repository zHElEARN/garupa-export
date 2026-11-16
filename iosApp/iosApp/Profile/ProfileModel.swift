import Foundation
import GarupaKit
import Observation
import SharedLogic

/// Owns conversion, its result, and retrying the latest response after a data refresh.
@MainActor
@Observable
final class ProfileModel {
    enum State {
        case empty, parsing
        case ready(ProfileDocument)
        case failed(String, refreshSuggested: Bool)
    }

    private(set) var state = State.empty
    private(set) var refreshingCatalog = false
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
        refreshingCatalog = false
        state = .empty
        onChange?()
    }

    func parse(_ response: CapturedResponse, refreshCatalog: Bool = false) {
        parseTask?.cancel()
        latestCapture = response
        refreshingCatalog = refreshCatalog
        state = .parsing
        onChange?()
        parseTask = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            do {
                if refreshCatalog {
                    await catalog.refresh()
                    try Task.checkCancellation()
                    if let error = catalog.error { throw GarupaError.message(error) }
                }
                try Task.checkCancellation()
                refreshingCatalog = false
                onChange?()
                guard let data = catalog.cache?.catalog else { throw GarupaError.message("请先下载 Bestdori 卡牌数据") }
                let document = try await Task.detached(priority: .userInitiated) {
                    try ProfileExporter.export(response, catalog: data)
                }.value
                if Task.isCancelled {
                    try? FileManager.default.removeItem(at: document.url)
                    return
                }
                state = .ready(document)
                onChange?()
            } catch {
                guard !Task.isCancelled else { return }
                refreshingCatalog = false
                let catalogMismatch = (error as NSError).userInfo["KotlinException"] is CatalogMismatchException
                state = .failed(error.localizedDescription,
                                refreshSuggested: refreshCatalog || catalogMismatch || catalog.cache == nil)
                onChange?()
            }
        }
    }

    func retry(refreshCatalog: Bool = false) {
        guard let latestCapture else { return }
        if case .parsing = state { return }
        parse(latestCapture, refreshCatalog: refreshCatalog)
    }
}
