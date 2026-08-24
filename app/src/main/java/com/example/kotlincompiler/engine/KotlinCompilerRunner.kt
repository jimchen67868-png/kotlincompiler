package com.example.kotlincompiler.engine

import android.content.Context
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class KotlinCompilerRunner(private val context: Context) {

    fun compile(
        sourceRoots: List<File>,
        classpath: List<File>,
        destinationDir: File,
        moduleName: String = "app"
    ): ProcessRunner.Result {
        destinationDir.mkdirs()

        val args = buildList {
            add("-d"); add(destinationDir.absolutePath)
            add("-classpath"); add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            add("-module-name"); add(moduleName)
            add("-no-stdlib")
            add("-no-reflect")
            add("-no-jdk")
            add("-kotlin-home"); add(context.filesDir.absolutePath)
            sourceRoots.forEach { add(it.absolutePath) }
        }.toTypedArray()

        val outputBuffer = ByteArrayOutputStream()
        val printStream = PrintStream(outputBuffer)

        val exitCode = try {
            val result = K2JVMCompiler().exec(printStream, *args)
            result.ordinal
        } catch (e: Exception) {
            printStream.println("Compiler threw: ${e.stackTraceToString()}")
            -1
        }

        val log = outputBuffer.toString(Charsets.UTF_8.name())
        return ProcessRunner.Result(exitCode = exitCode, stdout = log, stderr = "")
    }
}
