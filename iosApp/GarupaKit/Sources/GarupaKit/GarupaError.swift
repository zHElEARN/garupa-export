import Foundation

public enum GarupaError: LocalizedError {
    case message(String)

    public var errorDescription: String? {
        switch self { case .message(let message): return message }
    }
}

