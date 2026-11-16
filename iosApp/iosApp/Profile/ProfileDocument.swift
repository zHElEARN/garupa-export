import Foundation

struct ProfileDocument: Identifiable, Sendable {
    let id = UUID()
    let name: String
    let json: String
    let cardCount: Int
    let areaItemCount: Int
    let potentialCount: Int
    let warnings: [String]
    let url: URL
}
