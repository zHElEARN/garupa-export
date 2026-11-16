import Foundation
import GarupaKit
import SharedLogic

enum ProfileExporter {
    static func export(_ response: CapturedResponse, catalog: CardCatalog) throws -> ProfileDocument {
        let protobuf = try ResponseDecoder.decode(response.body, contentEncoding: response.contentEncoding)
        let converter = ProfileConverter(catalog: catalog)
        let result = try converter.convertBase64(protobuf: protobuf.base64EncodedString())
        let directory = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
        let file = directory.appendingPathComponent("bestdori-\(UUID().uuidString).json")
        try result.json.write(to: file, atomically: true, encoding: .utf8)
        return ProfileDocument(name: result.name, json: result.json,
                               cardCount: Int(result.cardCount), areaItemCount: Int(result.areaItemCount),
                               potentialCount: Int(result.potentialCount), warnings: result.warnings,
                               url: file)
    }
}
