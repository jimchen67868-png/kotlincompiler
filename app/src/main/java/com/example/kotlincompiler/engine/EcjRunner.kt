package com.example.kotlincompiler.engine

import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object EcjRunner {

    fun compile(javaFile: File, classpath: List<File>, destinationDir: File): ProcessRunner.Result {
        destinationDir.mkdirs()

        val cp = classpath.joinToString(File.pathSeparator) { it.absolutePath }
        val commandLine = listOf(
            "-source", "1.8",
            "-target", "1.8",
            "-nowarn",
            "-bootclasspath", "\"$cp\"",
            "-classpath", "\"$cp\"",
            "-d", "\"${destinationDir.absolutePath}\"",
            "\"${javaFile.absolutePath}\""
        ).joinToString(" ")

        val outWriter = StringWriter()
        val errWriter = StringWriter()

        val originalSpecVersion = System.getProperty("java.specification.version")
        System.setProperty("java.specification.version", "1.8")

        val success = try {
            BatchCompiler.compile(commandLine, PrintWriter(outWriter), PrintWriter(errWriter), null)
        } catch (e: Exception) {
            errWriter.write("ECJ invocation error: ${e.stackTraceToString()}")
            false
        } finally {
            if (originalSpecVersion != null) {
                System.setProperty("java.specification.version", originalSpecVersion)
            } else {
                System.clearProperty("java.specification.version")
            }
        }

        val log = outWriter.toString() + errWriter.toString()
        return ProcessRunner.Result(exitCode = if (success) 0 else -1, stdout = log, stderr = "")
    }
}
