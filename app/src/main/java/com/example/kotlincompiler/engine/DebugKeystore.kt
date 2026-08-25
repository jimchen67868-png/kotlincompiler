package com.example.kotlincompiler.engine

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

data class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

object DebugKeystore {
    private const val ALIAS = "androiddebugkey"
    private val PASSWORD = "android".toCharArray()

    fun getOrCreate(context: Context): SigningIdentity {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val ksFile = File(context.filesDir, "debug.p12")
        val keyStore = KeyStore.getInstance("PKCS12", "BC")

        if (ksFile.exists()) {
            ksFile.inputStream().use { keyStore.load(it, PASSWORD) }
            val privateKey = keyStore.getKey(ALIAS, PASSWORD) as PrivateKey
            val cert = keyStore.getCertificate(ALIAS) as X509Certificate
            return SigningIdentity(privateKey, cert)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(365L * 30))
        val subject = X500Name("CN=Android Debug,O=Android,C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, now, notAfter, subject, keyPair.public
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certBuilder.build(contentSigner))

        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(cert))
        ksFile.outputStream().use { keyStore.store(it, PASSWORD) }

        return SigningIdentity(keyPair.private, cert)
    }
}
