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
            "-source", "17",
            "-target", "17",
            "-nowarn",
            "-classpath", "\"$cp\"",
            "-d", "\"${destinationDir.absolutePath}\"",
            "\"${javaFile.absolutePath}\""
        ).joinToString(" ")

        val outWriter = StringWriter()
        val errWriter = StringWriter()

        val success = try {
            BatchCompiler.compile(commandLine, PrintWriter(outWriter), PrintWriter(errWriter), null)
        } catch (e: Exception) {
            errWriter.write("ECJ invocation error: ${e.stackTraceToString()}")
            false
        }

        val log = outWriter.toString() + errWriter.toString()
        return ProcessRunner.Result(exitCode = if (success) 0 else -1, stdout = log, stderr = "")
    }
}
