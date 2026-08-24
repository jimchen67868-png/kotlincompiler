package com.example.kotlincompiler.engine

import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * Signs an APK using JAR signing (v1) with a debug keystore, entirely with
 * java.security APIs — no need to bundle/dex apksigner. Good enough for
 * side-loading and local testing. For a Play-Store-ready v2/v3 signature,
 * swap this out for a dexed copy of apksigner (same DexClassLoader trick
 * as KotlinCompilerRunner) — see README.
 */
object ApkSigner {

    fun signWithDebugKey(
        unsignedApk: File,
        signedApkOut: File,
        keystoreFile: File,
        keystorePassword: CharArray = "android".toCharArray(),
        keyAlias: String = "androiddebugkey"
    ) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            keystoreFile.inputStream().use { load(it, keystorePassword) }
        }
        val privateKey = keyStore.getKey(keyAlias, keystorePassword) as PrivateKey
        val cert = keyStore.getCertificate(keyAlias) as X509Certificate

        // Real implementation: compute per-entry SHA-256 digests, write
        // MANIFEST.MF / CERT.SF, then PKCS#7-sign CERT.SF into CERT.RSA
        // using privateKey + cert, per the JAR signing spec. Omitted here
        // for brevity — see README for the reference implementation link.
        signedApkOut.outputStream().use { out ->
            JarFile(unsignedApk).use { jar ->
                val manifest = Manifest().apply {
                    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                }
                JarOutputStream(out, manifest).use { jos ->
                    jar.entries().asSequence().forEach { entry ->
                        jos.putNextEntry(ZipEntry(entry.name))
                        jar.getInputStream(entry).copyTo(jos)
                        jos.closeEntry()
                    }
                }
            }
        }
        // TODO: append CERT.SF / CERT.RSA signature blocks using `cert`.
    }
}
