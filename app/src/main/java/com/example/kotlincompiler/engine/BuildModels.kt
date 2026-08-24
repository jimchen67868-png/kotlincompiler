package com.example.kotlincompiler.engine

import java.io.File

/**
 * Minimal single-module project description.
 * (Multi-module support = list of these + a merge step; see README Phase 2.)
 */
data class KotlinProject(
    val projectDir: File,       // root containing src/, res/, AndroidManifest.xml
    val srcDir: File = File(projectDir, "src"),
    val resDir: File = File(projectDir, "res"),
    val manifestFile: File = File(projectDir, "AndroidManifest.xml"),
    val applicationId: String,
    val outputDir: File         // where the final .apk is written
)

sealed class BuildStep(val label: String) {
    object CompileKotlin : BuildStep("Compiling Kotlin sources")
    object DexClasses : BuildStep("Converting bytecode to DEX")
    object CompileResources : BuildStep("Compiling resources (aapt2)")
    object LinkResources : BuildStep("Linking resources + manifest")
    object PackageApk : BuildStep("Packaging APK")
    object AlignApk : BuildStep("Zip-aligning APK")
    object SignApk : BuildStep("Signing APK")
}

data class StepResult(
    val step: BuildStep,
    val success: Boolean,
    val log: String,
    val durationMs: Long
)

class BuildException(message: String, val log: String) : Exception(message)

/** Simple callback interface so the UI can stream progress. */
fun interface BuildListener {
    fun onStepFinished(result: StepResult)
}
