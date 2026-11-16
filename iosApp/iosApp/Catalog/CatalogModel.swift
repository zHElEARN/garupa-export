import Foundation
import GarupaKit
import Observation
import SharedLogic

@MainActor
@Observable
final class CatalogModel {
    private(set) var cache: CatalogCache?
    private(set) var loading = true
    private(set) var error: String?
    private(set) var upToDate = false
    private var activated = false
    private var activation: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?

    func activate() async {
        if let activation { await activation.value; return }
        guard !activated else { return }
        let task = Task {
            cache = await Task.detached { try? CatalogStore.load() }.value
            activated = true
            loading = false
        }
        activation = task
        await task.value
        activation = nil
    }

    func ensureAvailable() async throws {
        await activate()
        try Task.checkCancellation()
        if cache != nil { return }
        await refresh()
        try Task.checkCancellation()
        guard cache != nil else { throw GarupaError.message(error ?? "卡牌数据下载失败，请稍后重试。") }
    }

    func cancelRefresh() { refreshTask?.cancel() }

    func refresh() async {
        if let refreshTask { await refreshTask.value; return }
        await activate()
        guard !Task.isCancelled else { return }
        // Another caller may have started a refresh while activation was suspended.
        if let refreshTask { await refreshTask.value; return }
        loading = true
        error = nil
        upToDate = false
        let previous = cache
        let task = Task {
            defer { loading = false }
            do {
                let worker = Task.detached {
                    let downloaded = try await CatalogClient.download(previous: previous)
                    try Task.checkCancellation()
                    if downloaded !== previous { try CatalogStore.save(downloaded) }
                    return downloaded
                }
                let updated = try await withTaskCancellationHandler {
                    try await worker.value
                } onCancel: {
                    worker.cancel()
                }
                try Task.checkCancellation()
                cache = updated
                upToDate = updated === previous
            } catch is CancellationError {
                // Ending initial setup cancels its download without presenting an error.
            } catch {
                guard !Task.isCancelled else { return }
                self.error = "\(error.localizedDescription)。请稍后重试" +
                    (previous == nil ? "" : "，仍可使用已缓存的数据")
            }
        }
        refreshTask = task
        await task.value
        refreshTask = nil
    }
}
