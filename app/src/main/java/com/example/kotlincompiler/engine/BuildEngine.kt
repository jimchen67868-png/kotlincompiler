package com.example.kotlincompiler.engine

import com.example.kotlincompiler.ToolPaths
import android.content.Context
import java.io.File

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

        val compiledResZip = File(workDir, "compiled_res.zip")
        runStep(BuildStep.CompileResources) {
            ProcessRunner.run(
                binary = tools.aapt2Binary,
                args = listOf("compile", "--dir", project.resDir.absolutePath, "-o", compiledResZip.absolutePath),
                workingDir = workDir
            )
        }

        val linkedApk = File(workDir, "linked.apk")
        val rTxtFile = File(workDir, "R.txt")
        runStep(BuildStep.LinkResources) {
            ProcessRunner.run(
                binary = tools.aapt2Binary,
                args = listOf(
                    "link", "-I", tools.androidJar.absolutePath,
                    "--manifest", project.manifestFile.absolutePath,
                    "--min-sdk-version", "26",
                    "--target-sdk-version", "34",
                    "--output-text-symbols", rTxtFile.absolutePath,
                    "-o", linkedApk.absolutePath,
                    compiledResZip.absolutePath
                ),
                workingDir = workDir
            )
        }

        val rJavaDir = File(workDir, "r_src").apply { mkdirs() }
        val classesDir = File(workDir, "classes").apply { mkdirs() }
        runStep(BuildStep.GenerateRClass) {
            val packageName = extractManifestPackage(project.manifestFile) ?: project.applicationId
            val rJavaFile = RJavaGenerator.generate(rTxtFile, packageName, rJavaDir)
            EcjRunner.compile(rJavaFile, listOf(tools.androidJar), classesDir)
        }

        runStep(BuildStep.CompileKotlin) {
            val runner = KotlinCompilerRunner(context)
            runner.compile(
                sourceRoots = listOf(project.srcDir),
                classpath = listOf(tools.androidJar, tools.kotlinStdlibJar, classesDir),
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

    private fun runStep(step: BuildStep, action: () -> ProcessRunner.Result) {
        val start = System.currentTimeMillis()
        val result = action()
        val duration = System.currentTimeMillis() - start
        listener.onStepFinished(StepResult(step, result.ok, result.combinedLog, duration))
        if (!result.ok) {
            throw BuildException("${step.label} failed", result.combinedLog)
        }
    }

    private fun extractManifestPackage(manifestFile: File): String? {
        if (!manifestFile.exists()) return null
        val text = manifestFile.readText()
        return Regex("package\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }
}

object ApkPackager {
    fun mergeDexIntoApk(linkedApk: File, dexDir: File, outputApk: File) {
        val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }
            ?.sortedBy { it.name }
            ?: emptyList()

        java.util.zip.ZipFile(linkedApk).use { zip ->
            outputApk.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    zos.setLevel(9)

                    val allEntries = zip.entries().asSequence().toList()
                    val manifestEntry = allEntries.find { it.name == "AndroidManifest.xml" }
                    val otherEntries = allEntries.filterNot { it.name == "AndroidManifest.xml" }

                    manifestEntry?.let { writeEntry(zip, it, zos) }

                    dexFiles.forEachIndexed { index, dexFile ->
                        val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                        dexFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }

                    otherEntries.forEach { entry -> writeEntry(zip, entry, zos) }
                }
            }
        }
    }

    private fun writeEntry(
        zip: java.util.zip.ZipFile,
        entry: java.util.zip.ZipEntry,
        zos: java.util.zip.ZipOutputStream
    ) {
        val newEntry = java.util.zip.ZipEntry(entry.name)
        if (entry.method == java.util.zip.ZipEntry.STORED) {
            newEntry.method = java.util.zip.ZipEntry.STORED
            newEntry.size = entry.size
            newEntry.compressedSize = entry.size
            newEntry.crc = entry.crc
        } else {
            newEntry.method = java.util.zip.ZipEntry.DEFLATED
        }
        zos.putNextEntry(newEntry)
        zip.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
