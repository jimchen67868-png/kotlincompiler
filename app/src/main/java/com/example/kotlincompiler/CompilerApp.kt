package com.example.kotlincompiler

import android.app.Application
import java.io.File

class CompilerApp : Application() {

    lateinit var toolPaths: ToolPaths
        private set

    override fun onCreate() {
        super.onCreate()
        toolPaths = ToolPaths(this)
    }
}

data class ToolPaths(private val app: Application) {
    private val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    private val filesDir = app.filesDir

    val aapt2Binary = File(nativeLibDir, "libaapt2_bin.so")

    val d8Dex = File(filesDir, "tools/d8-dex.jar")
    val androidJar = File(filesDir, "tools/android.jar")
    val kotlinStdlibJar = File(filesDir, "tools/kotlin-stdlib.jar")

    fun ensureAssetsExtracted() {
        File(filesDir, "tools").mkdirs()
        val required = mapOf(
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
            if (assetName == "d8-dex.jar" && dest.canWrite()) {
                dest.setWritable(false, false)
            }
        }
    }
}
