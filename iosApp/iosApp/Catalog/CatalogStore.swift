import Foundation
import SharedLogic

/// Reads and atomically replaces the device-local cache, independently of networking.
enum CatalogStore {
    static func load() throws -> CatalogCache? {
        let file = try fileURL()
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= Int(CatalogCache.companion.MAX_JSON_BYTES) else {
            throw NSError(domain: "BestdoriCatalog", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "卡牌数据缓存为空或超过大小限制"])
        }
        return try CatalogCache(cacheJson: String(contentsOf: file, encoding: .utf8))
    }

    static func save(_ cache: CatalogCache) throws {
        try cache.toJsonString().write(to: fileURL(), atomically: true, encoding: .utf8)
    }

    private static func fileURL() throws -> URL {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                   appropriateFor: nil, create: true)
            .appendingPathComponent("Bestdori", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("cards-v1.json")
    }
}
