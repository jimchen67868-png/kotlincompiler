package com.example.kotlincompiler.engine

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Loads kotlin-compiler-dex.jar (the Kotlin compiler, pre-converted to DEX
 * at build time) and invokes its entry point reflectively. Running it
 * in-process avoids needing a JVM, which Android does not have; ART can
 * execute the DEX-ified compiler directly since it's just more bytecode
 * to it.
 *
 * The compiler jar is itself written in Kotlin, so it needs kotlin-stdlib
 * classes (e.g. kotlin.jvm.internal.Intrinsics) available on its OWN
 * classloader to even instantiate — separate from the plain
 * kotlin-stdlib.jar passed via -classpath for compiling the *user's*
 * source below. That's why stdlibDexJar is also on the DexClassLoader path.
 */
class KotlinCompilerRunner(
    private val context: Context,
    private val compilerDexJar: File,
    private val stdlibDexJar: File
) {

    fun compile(
        sourceRoots: List<File>,
        classpath: List<File>,
        destinationDir: File,
        moduleName: String = "app"
    ): ProcessRunner.Result {
        destinationDir.mkdirs()

        val optimizedDir = File(context.codeCacheDir, "dex_opt").apply { mkdirs() }
        val dexPath = listOf(compilerDexJar, stdlibDexJar).joinToString(File.pathSeparator) { it.absolutePath }
        val loader = DexClassLoader(
            dexPath,
            optimizedDir.absolutePath,
            null,
            ClassLoader.getSystemClassLoader()
        )

        // org.jetbrains.kotlin.cli.jvm.K2JVMCompiler exposes a CLI-style
        // entry point: exec(PrintStream, vararg args): ExitCode
        val compilerClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compilerInstance = compilerClass.getDeclaredConstructor().newInstance()
        val execMethod = compilerClass.getMethod(
            "exec",
            PrintStream::class.java,
            Array<String>::class.java
        )

        val args = buildList {
            add("-d"); add(destinationDir.absolutePath)
            add("-classpath"); add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            add("-module-name"); add(moduleName)
            add("-no-stdlib")   // stdlib supplied explicitly via classpath
            add("-no-reflect")
            sourceRoots.forEach { add(it.absolutePath) }
        }.toTypedArray()

        val outputBuffer = ByteArrayOutputStream()
        val printStream = PrintStream(outputBuffer)

        val exitCodeObj = execMethod.invoke(compilerInstance, printStream, args)
        // ExitCode is an enum; ordinal 0 == OK by kotlinc convention.
        val exitCode = try {
            val ordinalMethod = exitCodeObj.javaClass.getMethod("ordinal")
            ordinalMethod.invoke(exitCodeObj) as Int
        } catch (e: Exception) {
            -1
        }

        val log = outputBuffer.toString(Charsets.UTF_8.name())
        return ProcessRunner.Result(exitCode = exitCode, stdout = log, stderr = "")
    }
}
