import Foundation
import GarupaKit
import SharedLogic

enum ProfileExporter {
    static func export(_ response: CapturedResponse) throws -> ProfileDocument {
        let protobuf = try ResponseDecoder.decode(response.body, contentEncoding: response.contentEncoding)
        guard let masterURL = Bundle.main.url(forResource: "master-episodes", withExtension: "json") else {
            throw GarupaError.message("App 缺少 master 映射资源")
        }
        let converter = try ProfileConverter(masterJson: String(contentsOf: masterURL, encoding: .utf8))
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
