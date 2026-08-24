package com.example.kotlincompiler.engine

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

/**
 * Loads d8-dex.jar (the d8 dexer, pre-converted to DEX at build time —
 * see app/build.gradle.kts "prepareCompilerAssets") and invokes its
 * programmatic API (D8Command + D8.run) via reflection. Deliberately does
 * NOT call D8's CLI main(String[]) entry point, because that method is
 * known to call System.exit() on failure, which would kill this app's
 * whole process rather than just failing the build step.
 */
class D8Runner(private val context: Context, private val d8DexJar: File) {

    fun dex(
        classFiles: List<File>,
        libraryJar: File,
        outputDir: File,
        minApi: Int = 26
    ): ProcessRunner.Result {
        if (classFiles.isEmpty()) {
            return ProcessRunner.Result(0, "d8: nothing to dex (no .class files found)", "")
        }

        outputDir.mkdirs()
        val optimizedDir = File(context.codeCacheDir, "d8_dex_opt").apply { mkdirs() }
        val loader = DexClassLoader(
            d8DexJar.absolutePath,
            optimizedDir.absolutePath,
            null,
            ClassLoader.getSystemClassLoader()
        )

        return try {
            val commandClass = loader.loadClass("com.android.tools.r8.D8Command")
            val builderClass = loader.loadClass("com.android.tools.r8.D8Command\$Builder")
            val outputModeClass = loader.loadClass("com.android.tools.r8.OutputMode")
            val d8Class = loader.loadClass("com.android.tools.r8.D8")

            val builder = commandClass.getMethod("builder").invoke(null)
            val pathArrayType = ReflectArray.newInstance(Path::class.java, 0).javaClass

            val programPaths = ReflectArray.newInstance(Path::class.java, classFiles.size)
            classFiles.forEachIndexed { i, f -> ReflectArray.set(programPaths, i, f.toPath()) }
            builderClass.getMethod("addProgramFiles", pathArrayType).invoke(builder, programPaths)

            val libPaths = ReflectArray.newInstance(Path::class.java, 1)
            ReflectArray.set(libPaths, 0, libraryJar.toPath())
            builderClass.getMethod("addLibraryFiles", pathArrayType).invoke(builder, libPaths)

            val dexIndexedMode = outputModeClass.getMethod("valueOf", String::class.java)
                .invoke(null, "DexIndexed")
            builderClass.getMethod("setOutput", Path::class.java, outputModeClass)
                .invoke(builder, outputDir.toPath(), dexIndexedMode)

            builderClass.getMethod("setMinApiLevel", Int::class.javaPrimitiveType)
                .invoke(builder, minApi)

            val command = builderClass.getMethod("build").invoke(builder)
            d8Class.getMethod("run", commandClass).invoke(null, command)

            ProcessRunner.Result(
                exitCode = 0,
                stdout = "d8: dexed ${classFiles.size} class file(s) -> ${outputDir.absolutePath}",
                stderr = ""
            )
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            ProcessRunner.Result(-1, "", "d8 compilation failed: ${cause?.message ?: e.message}")
        } catch (e: Exception) {
            ProcessRunner.Result(-1, "", "d8 invocation error: ${e.message}")
        }
    }
}
