package com.example.kotlincompiler.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a bundled native binary (d8, aapt2, zipalign) as a subprocess.
 * These binaries are extracted by the OS package manager into
 * applicationInfo.nativeLibraryDir, which is always executable —
 * unlike arbitrary files copied at runtime, which are subject to
 * W^X restrictions on modern Android and won't run.
 */
object ProcessRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val combinedLog: String get() = stdout + (if (stderr.isNotBlank()) "\n$stderr" else "")
        val ok: Boolean get() = exitCode == 0
    }

    fun run(
        binary: File,
        args: List<String>,
        workingDir: File? = null,
        timeoutSeconds: Long = 120,
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        require(binary.exists()) { "Tool binary not found: ${binary.absolutePath}" }

        val command = mutableListOf(binary.absolutePath).apply { addAll(args) }
        val pb = ProcessBuilder(command)
            .redirectErrorStream(false)
            .apply { workingDir?.let { directory(it) } }

        pb.environment().putAll(extraEnv)

        val process = pb.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Result(-1, "", "Process timed out after ${timeoutSeconds}s: ${binary.name}")
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Result(process.exitValue(), stdout, stderr)
    }
}
