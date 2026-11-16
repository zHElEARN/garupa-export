import Foundation
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

    func activate() async {
        guard !activated else { return }
        activated = true
        cache = await Task.detached { try? CatalogStore.load() }.value
        loading = false
        if cache == nil { await refresh() }
    }

    func refresh() async {
        guard !loading else { return }
        loading = true
        error = nil
        upToDate = false
        let previous = cache
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
}
