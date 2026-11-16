import Foundation
import CommonCrypto
import zlib

public enum ResponseDecoder {
    public static func decode(_ body: Data, contentEncoding: String?) throws -> Data {
        guard !body.isEmpty, body.count <= CapturedResponse.maximumBytes else { throw GarupaError.message("响应体为空或超过大小限制") }
        var encrypted = body
        for encoding in (contentEncoding ?? "").split(separator: ",").reversed() {
            switch encoding.trimmingCharacters(in: .whitespaces).lowercased() {
            case "", "identity": break
            case "gzip", "x-gzip": encrypted = try gunzip(encrypted)
            default: throw GarupaError.message("不支持的 HTTP 压缩格式：\(encoding)")
            }
        }
        guard !encrypted.isEmpty, encrypted.count % kCCBlockSizeAES128 == 0 else {
            throw GarupaError.message("响应不是完整的 AES 数据块")
        }
        let key = Array("wakakabiliwakaka".utf8)
        let iv = Array("biliwakakawohaha".utf8)
        var decrypted = Data(count: encrypted.count)
        var written = 0
        let capacity = decrypted.count
        let status = decrypted.withUnsafeMutableBytes { output in
            encrypted.withUnsafeBytes { input in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), 0,
                                keyBytes.baseAddress, key.count, ivBytes.baseAddress,
                                input.baseAddress, encrypted.count, output.baseAddress, capacity, &written)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw GarupaError.message("游戏响应解密失败（\(status)）") }
        decrypted.count = written
        guard let padding = decrypted.last, (1...16).contains(padding), Int(padding) <= decrypted.count else {
            throw GarupaError.message("游戏响应解密失败：填充长度无效")
        }
        // ISO10126 defines only the final padding-length byte.
        decrypted.removeLast(Int(padding))
        return decrypted.starts(with: [0x1f, 0x8b]) ? try gunzip(decrypted) : decrypted
    }

    private static func gunzip(_ bytes: Data) throws -> Data {
        var stream = z_stream()
        guard inflateInit2_(&stream, 15 + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            throw GarupaError.message("无法初始化 gzip 解压")
        }
        defer { inflateEnd(&stream) }
        return try bytes.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(bytes.count)
            var output = Data()
            var buffer = [UInt8](repeating: 0, count: 32 * 1024)
            while true {
                let result: Int32 = buffer.withUnsafeMutableBytes { chunk in
                    stream.next_out = chunk.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(chunk.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                let count = buffer.count - Int(stream.avail_out)
                guard output.count + count <= CapturedResponse.maximumBytes else { throw GarupaError.message("解压后的存档超过大小限制") }
                output.append(contentsOf: buffer.prefix(count))
                if result == Z_STREAM_END { return output }
                guard result == Z_OK, count > 0 || stream.avail_in > 0 else { throw GarupaError.message("gzip 数据不完整") }
            }
        }
    }
}
