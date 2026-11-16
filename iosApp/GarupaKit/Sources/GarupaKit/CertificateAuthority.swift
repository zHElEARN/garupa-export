import Foundation
import CryptoKit
import Security
import NIOSSL
import X509

/// A device-local CA. Only its public certificate is included in the installable profile.
// The identity is immutable; the TLS-context cache is protected by lock.
public final class CertificateAuthority: @unchecked Sendable {
    private static let keychainAccount = "garupa-export.local-ca.v1"
    private let key: P256.Signing.PrivateKey
    private let root: X509.Certificate
    private let lock = NSLock()
    private var contexts: [String: NIOSSLContext] = [:]

    public init() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.zhelearn.garupaexport",
            kSecAttrAccount as String: Self.keychainAccount,
            kSecReturnData as String: true,
        ]
        var stored: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &stored)
        if status == errSecSuccess, let data = stored as? Data {
            let identity = try JSONDecoder().decode(StoredIdentity.self, from: data)
            key = try P256.Signing.PrivateKey(rawRepresentation: identity.key)
            root = try X509.Certificate(derEncoded: Array(identity.certificate))
        } else if status == errSecItemNotFound {
            let generated = P256.Signing.PrivateKey()
            let name = try DistinguishedName {
                CommonName("Garupa Export Local CA")
                OrganizationName("Garupa Export")
            }
            let now = Date()
            let certificate = try X509.Certificate(
                version: .v3, serialNumber: .init(), publicKey: .init(generated.publicKey),
                notValidBefore: now.addingTimeInterval(-86400),
                notValidAfter: now.addingTimeInterval(10 * 365 * 86400),
                issuer: name, subject: name,
                extensions: try X509.Certificate.Extensions {
                    Critical(BasicConstraints.isCertificateAuthority(maxPathLength: 0))
                    Critical(KeyUsage(keyCertSign: true, cRLSign: true))
                },
                issuerPrivateKey: .init(generated)
            )
            let encoded = try JSONEncoder().encode(StoredIdentity(
                key: generated.rawRepresentation,
                certificate: Data(try certificate.serializeAsPEM().derBytes)
            ))
            var attributes = query
            attributes.removeValue(forKey: kSecReturnData as String)
            attributes[kSecValueData as String] = encoded
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(attributes as CFDictionary, nil)
            guard added == errSecSuccess else { throw GarupaError.message("无法保存本地 CA（\(added)）") }
            key = generated
            root = certificate
        } else {
            throw GarupaError.message("无法读取本地 CA（\(status)）")
        }
    }

    public func configurationProfile() throws -> Data {
        let rootData = Data(try root.serializeAsPEM().derBytes)
        let profile: [String: Any] = [
            "PayloadType": "Configuration", "PayloadVersion": 1,
            "PayloadIdentifier": "com.zhelearn.garupaexport.ca-profile",
            "PayloadUUID": UUID().uuidString,
            "PayloadDisplayName": "Garupa Export",
            "PayloadContent": [[
                "PayloadType": "com.apple.security.root", "PayloadVersion": 1,
                "PayloadIdentifier": "com.zhelearn.garupaexport.ca",
                "PayloadUUID": UUID().uuidString,
                "PayloadDisplayName": "Garupa Export Local CA",
                "PayloadCertificateFileName": "garupa-export-ca.cer",
                "PayloadContent": rootData,
            ]],
        ]
        return try PropertyListSerialization.data(fromPropertyList: profile, format: .xml, options: 0)
    }

    /// Uses system trust only: supplying our root as a chain member does not make it a trust anchor.
    public func isTrusted() throws -> Bool {
        let host = "l1-prod-all-bd.bilibiligame.net"
        let (leaf, _) = try issue(host: host)
        let certificates = try [leaf, root].map { try SecCertificate.makeWithCertificate($0) }
        var trust: SecTrust?
        let status = SecTrustCreateWithCertificates(certificates as CFArray, SecPolicyCreateSSL(true, host as CFString), &trust)
        guard status == errSecSuccess, let trust else { throw GarupaError.message("无法检测 CA 信任状态（\(status)）") }
        SecTrustSetNetworkFetchAllowed(trust, false)
        return SecTrustEvaluateWithError(trust, nil)
    }

    func serverContext(host: String) throws -> NIOSSLContext {
        lock.lock()
        defer { lock.unlock() }
        if let cached = contexts[host] { return cached }
        let (leaf, leafKey) = try issue(host: host)
        let chain = try [leaf, root].map {
            try NIOSSLCertificate(bytes: Array(try $0.serializeAsPEM().derBytes), format: .der)
        }
        let privateKey = try NIOSSLPrivateKey(bytes: Array(leafKey.pemRepresentation.utf8), format: .pem)
        var configuration = TLSConfiguration.makeServerConfiguration(
            certificateChain: chain.map { .certificate($0) }, privateKey: .privateKey(privateKey)
        )
        configuration.applicationProtocols = ["http/1.1"]
        configuration.minimumTLSVersion = .tlsv12
        let context = try NIOSSLContext(configuration: configuration)
        if contexts.count >= 32 { contexts.removeAll() }
        contexts[host] = context
        return context
    }

    private func issue(host: String) throws -> (X509.Certificate, P256.Signing.PrivateKey) {
        let leafKey = P256.Signing.PrivateKey()
        let now = Date()
        let leaf = try X509.Certificate(
            version: .v3, serialNumber: .init(), publicKey: .init(leafKey.publicKey),
            notValidBefore: now.addingTimeInterval(-86400),
            notValidAfter: min(now.addingTimeInterval(30 * 86400), root.notValidAfter),
            issuer: root.subject,
            subject: try DistinguishedName { CommonName(host) },
            extensions: try X509.Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
                Critical(KeyUsage(digitalSignature: true))
                try ExtendedKeyUsage([.serverAuth])
                SubjectAlternativeNames([.dnsName(host)])
            },
            issuerPrivateKey: .init(key)
        )
        return (leaf, leafKey)
    }

    private struct StoredIdentity: Codable {
        let key: Data
        let certificate: Data
    }
}
