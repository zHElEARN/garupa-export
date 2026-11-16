import Foundation
import SharedLogic

/// Fetches a validated snapshot without changing persistent data or UI state.
enum CatalogClient {
    static func download(previous: CatalogCache?) async throws -> CatalogCache {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 45
        config.connectionProxyDictionary = [:]
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: URL(string: CardCatalog.companion.URL)!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("GarupaExport/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue(previous?.etag, forHTTPHeaderField: "If-None-Match")
        request.setValue(previous?.lastModified, forHTTPHeaderField: "If-Modified-Since")
        let (temporary, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: temporary) }
        guard let http = response as? HTTPURLResponse else { throw failure("Bestdori 响应无效") }
        if http.statusCode == 304 {
            guard let previous else { throw failure("Bestdori 未返回卡牌数据") }
            return previous
        }
        guard http.statusCode == 200 else {
            if http.statusCode == 429 { throw failure("Bestdori 请求过于频繁，请等待服务器允许后再刷新") }
            throw failure("Bestdori 下载失败（HTTP \(http.statusCode)）")
        }
        let size = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= Int(CardCatalog.companion.MAX_JSON_BYTES) else {
            throw failure("Bestdori 卡牌数据为空或超过大小限制")
        }
        return try CatalogCache(
            catalogJson: String(contentsOf: temporary, encoding: .utf8),
            updatedAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
            etag: http.value(forHTTPHeaderField: "ETag"),
            lastModified: http.value(forHTTPHeaderField: "Last-Modified")
        )
    }

    private static func failure(_ message: String) -> NSError {
        NSError(domain: "BestdoriCatalog", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
