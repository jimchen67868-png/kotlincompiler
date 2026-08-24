package com.example.kotlincompiler

import android.app.Application
import java.io.File

/**
 * Holds paths to everything the build engine needs:
 *  - native command-line tools (d8, aapt2, zipalign) extracted by the
 *    package manager into nativeLibraryDir because they live under
 *    src/main/jniLibs/<abi>/
 *  - the dexed Kotlin compiler jar and android.jar, shipped as raw assets
 *    and copied to internal storage once so external processes can read them.
 */
class CompilerApp : Application() {

    lateinit var toolPaths: ToolPaths
        private set

    override fun onCreate() {
        super.onCreate()
        toolPaths = ToolPaths(this)
        // NOTE: deliberately NOT calling ensureAssetsExtracted() here.
        // The compiler jars won't exist until you add them per the README,
        // and extracting eagerly at app-launch time means a missing asset
        // crashes the whole app before the UI even shows. Extraction now
        // happens lazily, right before a build starts (see BuildEngine),
        // where a missing-asset failure can be shown as a normal build
        // error instead of a force-close.
    }
}

data class ToolPaths(private val app: Application) {
    private val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    private val filesDir = app.filesDir

    // Native binaries. Renamed with a lib*.so prefix (see README) so the
    // package manager will extract+chmod them for us automatically.
    // Only aapt2 is truly native (C++); d8 and the Kotlin compiler are
    // pure JVM bytecode and run in-process via DexClassLoader instead.
    val aapt2Binary = File(nativeLibDir, "libaapt2_bin.so")

    // Extracted once from assets on first run.
    val kotlinCompilerDex = File(filesDir, "tools/kotlin-compiler-dex.jar")
    val kotlinStdlibDex = File(filesDir, "tools/kotlin-stdlib-dex.jar")
    val d8Dex = File(filesDir, "tools/d8-dex.jar")
    val androidJar = File(filesDir, "tools/android.jar")
    val kotlinStdlibJar = File(filesDir, "tools/kotlin-stdlib.jar")

    /**
     * Copies compiler assets out of the APK into internal storage so
     * external tool processes (and DexClassLoader) can read them as
     * normal files. Called lazily right before a build starts, not at
     * app launch, and throws a descriptive exception rather than an
     * opaque FileNotFoundException if an asset is missing.
     */
    // Which extracted assets get loaded via DexClassLoader and therefore
    // MUST be made read-only after extraction — Android 10+ (API 29+)
    // refuses to DexClassLoader-load a file ART considers "writable"
    // (SecurityException: "Writable dex file ... is not allowed"), as an
    // anti-tampering measure against code rewritten after being loaded.
    // android.jar / kotlin-stdlib.jar (the plain, non-dexed copy used only
    // as a -classpath argument) are never dex-loaded, so they're exempt.
    private val dexLoadedAssetNames = setOf("kotlin-compiler-dex.jar", "kotlin-stdlib-dex.jar", "d8-dex.jar")

    fun ensureAssetsExtracted() {
        File(filesDir, "tools").mkdirs()
        val required = mapOf(
            "kotlin-compiler-dex.jar" to kotlinCompilerDex,
            "kotlin-stdlib-dex.jar" to kotlinStdlibDex,
            "d8-dex.jar" to d8Dex,
            "android.jar" to androidJar,
            "kotlin-stdlib.jar" to kotlinStdlibJar
        )
        required.forEach { (assetName, dest) ->
            if (!dest.exists()) {
                try {
                    app.assets.open("tools/$assetName").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: java.io.FileNotFoundException) {
                    throw IllegalStateException(
                        "Missing required compiler asset: assets/tools/$assetName. " +
                            "See README.md \"Preparing the compiler assets\" for how to obtain it.",
                        e
                    )
                }
            }
            if (assetName in dexLoadedAssetNames && dest.canWrite()) {
                dest.setWritable(false, false)
            }
        }
    }
}
