package com.zhelearn.garupaexport.capture.proxy

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal class CertificateAuthority(certificatePem: String, privateKeyPem: String) {
    // An explicit provider instance avoids replacing Android's platform BC provider.
    private val provider = BouncyCastleProvider()
    private val certificate = PEMParser(certificatePem.reader()).use { parser ->
        val holder = parser.readObject() as? X509CertificateHolder ?: error("Missing CA certificate PEM block")
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
    }
    private val privateKey: PrivateKey = PEMParser(privateKeyPem.reader()).use { parser ->
        val converter = JcaPEMKeyConverter().setProvider(provider)
        when (val value = parser.readObject()) {
            is PEMKeyPair -> converter.getPrivateKey(value.privateKeyInfo)
            is PrivateKeyInfo -> converter.getPrivateKey(value)
            else -> error("Unsupported CA private key format")
        }
    }
    private val contexts = object : LinkedHashMap<String, SSLContext>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?): Boolean = size > 32
    }

    init {
        certificate.checkValidity()
        require(certificate.basicConstraints >= 0) { "The supplied certificate is not a CA" }
        require(privateKey.algorithm in setOf("EC", "ECDSA")) { "The capture CA must use an EC private key" }
    }

    @Synchronized
    fun serverContext(host: String): SSLContext = contexts.getOrPut(host) {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val now = System.currentTimeMillis()
        val extensions = JcaX509ExtensionUtils()
        val issuerSki = X509CertificateHolder(certificate.encoded).getExtension(Extension.subjectKeyIdentifier)
            ?.parsedValue?.let { SubjectKeyIdentifier.getInstance(it).keyIdentifier }
            ?: extensions.createSubjectKeyIdentifier(certificate.publicKey).keyIdentifier
        val builder = JcaX509v3CertificateBuilder(
            certificate,
            BigInteger(159, SecureRandom()).add(BigInteger.ONE),
            Date(now - 86_400_000L),
            Date(minOf(now + 30L * 86_400_000L, certificate.notAfter.time)),
            X500Name("CN=$host"),
            pair.public,
        ).addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, host)))
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.public))
            // Include only the issuer key identifier in AKI.
            .addExtension(Extension.authorityKeyIdentifier, false, AuthorityKeyIdentifier(issuerSki))
        val holder = builder.build(JcaContentSignerBuilder("SHA384withECDSA").setProvider(provider).build(privateKey))
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        leaf.verify(certificate.publicKey)
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("leaf", pair.private, password, arrayOf(leaf, certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }
        SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
    }
}
