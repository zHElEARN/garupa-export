import SwiftUI
import UniformTypeIdentifiers

/// A snapshot of the selected profile for the system file exporter.
struct ProfileFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let contents: Data

    init(json: String) {
        contents = Data(json.utf8)
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        contents = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: contents)
    }
}
