package com.example.kotlincompiler.engine

import java.io.File
import com.android.apksig.ApkSigner as GoogleApkSigner

object ApkSigner {

    fun signWithDebugKey(
        unsignedApk: File,
        signedApkOut: File,
        identity: SigningIdentity,
        minSdkVersion: Int = 26
    ) {
        val signerConfig = GoogleApkSigner.SignerConfig.Builder(
            "debugkey",
            identity.privateKey,
            listOf(identity.certificate)
        ).build()

        GoogleApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApkOut)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdkVersion)
            .build()
            .sign()
    }
}
