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
        if cache != nil { return }
        await refresh()
        guard cache != nil else { throw GarupaError.message(error ?? "卡牌数据下载失败，请稍后重试。") }
    }

    func refresh() async {
        if let refreshTask { await refreshTask.value; return }
        await activate()
        // Another caller may have started a refresh while activation was suspended.
        if let refreshTask { await refreshTask.value; return }
        loading = true
        error = nil
        upToDate = false
        let previous = cache
        let task = Task {
            defer { loading = false }
            do {
                let updated = try await Task.detached {
                    let downloaded = try await CatalogClient.download(previous: previous)
                    if downloaded !== previous { try CatalogStore.save(downloaded) }
                    return downloaded
                }.value
                cache = updated
                upToDate = updated === previous
            } catch {
                self.error = "\(error.localizedDescription)。请稍后重试" +
                    (previous == nil ? "" : "，仍可使用已缓存的数据")
            }
        }
        refreshTask = task
        await task.value
        refreshTask = nil
    }
}
