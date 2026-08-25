package com.example.kotlincompiler.engine

import com.example.kotlincompiler.ToolPaths
import android.content.Context
import java.io.File

/**
 * Orchestrates: kotlinc (in-process, dexed) -> d8 (in-process, dexed) ->
 * aapt2 compile/link (native ARM binary) -> package -> zipalign
 * (pure Kotlin) -> sign.
 *
 * Each step is intentionally a separate function returning a StepResult
 * so the UI (BuildLogActivity) can stream progress and stop on first failure.
 */
class BuildEngine(
    private val context: Context,
    private val tools: ToolPaths,
    private val listener: BuildListener
) {

    private val workDir = File(context.cacheDir, "build_work")

    fun build(project: KotlinProject): File {
        try {
            tools.ensureAssetsExtracted()
        } catch (e: IllegalStateException) {
            throw BuildException(e.message ?: "Missing compiler assets", e.stackTraceToString())
        }

        workDir.deleteRecursively()
        workDir.mkdirs()

        val classesDir = File(workDir, "classes").apply { mkdirs() }
        runStep(BuildStep.CompileKotlin) {
            val runner = KotlinCompilerRunner(context)
            runner.compile(
                sourceRoots = listOf(project.srcDir),
                classpath = listOf(tools.androidJar, tools.kotlinStdlibJar),
                destinationDir = classesDir
            )
        }

        val dexDir = File(workDir, "dex").apply { mkdirs() }
        runStep(BuildStep.DexClasses) {
            val runner = D8Runner(context, tools.d8Dex)
            runner.dex(
                classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList(),
                libraryJar = tools.androidJar,
                outputDir = dexDir
            )
        }

        val compiledResZip = File(workDir, "compiled_res.zip")
        runStep(BuildStep.CompileResources) {
            ProcessRunner.run(
                binary = tools.aapt2Binary,
                args = listOf("compile", "--dir", project.resDir.absolutePath, "-o", compiledResZip.absolutePath),
                workingDir = workDir
            )
        }

        val linkedApk = File(workDir, "linked.apk")
        runStep(BuildStep.LinkResources) {
            ProcessRunner.run(
                binary = tools.aapt2Binary,
                args = listOf(
                    "link", "-I", tools.androidJar.absolutePath,
                    "--manifest", project.manifestFile.absolutePath,
                    "-o", linkedApk.absolutePath,
                    compiledResZip.absolutePath
                ),
                workingDir = workDir
            )
        }

        val unsignedApk = File(workDir, "unsigned.apk")
        runStep(BuildStep.PackageApk) {
            ApkPackager.mergeDexIntoApk(
                linkedApk = linkedApk,
                dexDir = dexDir,
                outputApk = unsignedApk
            )
            ProcessRunner.Result(0, "Packaged ${unsignedApk.name}", "")
        }

        val alignedApk = File(workDir, "aligned.apk")
        runStep(BuildStep.AlignApk) {
            ZipAligner.align(unsignedApk, alignedApk)
            ProcessRunner.Result(0, "Aligned -> ${alignedApk.name}", "")
        }

        val finalApk = File(project.outputDir, "${project.applicationId}.apk")
        runStep(BuildStep.SignApk) {
            val identity = DebugKeystore.getOrCreate(context)
            ApkSigner.signWithDebugKey(alignedApk, finalApk, identity)
            ProcessRunner.Result(0, "Signed -> ${finalApk.absolutePath}", "")
        }

        return finalApk
    }

    /** Runs a step, times it, reports it, and throws on failure to halt the pipeline. */
    private fun runStep(step: BuildStep, action: () -> ProcessRunner.Result) {
        val start = System.currentTimeMillis()
        val result = action()
        val duration = System.currentTimeMillis() - start
        listener.onStepFinished(StepResult(step, result.ok, result.combinedLog, duration))
        if (!result.ok) {
            throw BuildException("${step.label} failed", result.combinedLog)
        }
    }
}

/** Merges classes.dex (and classesN.dex for multidex) into the aapt2-linked resource APK. */
object ApkPackager {
    fun mergeDexIntoApk(linkedApk: File, dexDir: File, outputApk: File) {
        val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }
            ?.sortedBy { it.name }
            ?: emptyList()

        java.util.zip.ZipFile(linkedApk).use { zip ->
            outputApk.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    zip.entries().asSequence().forEach { entry ->
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    dexFiles.forEachIndexed { index, dexFile ->
                        val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                        dexFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}

