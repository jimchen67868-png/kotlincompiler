package com.example.kotlincompiler.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.kotlincompiler.CompilerApp
import com.example.kotlincompiler.databinding.ActivityBuildLogBinding
import com.example.kotlincompiler.engine.BuildEngine
import com.example.kotlincompiler.engine.BuildException
import com.example.kotlincompiler.engine.KotlinProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BuildLogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_DIR = "project_dir"
        const val EXTRA_OUTPUT_FOLDER_URI = "output_folder_uri"
    }

    private lateinit var binding: ActivityBuildLogBinding
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuildLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectDirPath = intent.getStringExtra(EXTRA_PROJECT_DIR) ?: return finish()
        val projectDir = File(projectDirPath)
        val outputFolderUriString = intent.getStringExtra(EXTRA_OUTPUT_FOLDER_URI)
        val outputFolderUri = outputFolderUriString?.let { Uri.parse(it) }

        val project = KotlinProject(
            projectDir = projectDir,
            applicationId = "com.example.generatedapp",
            outputDir = File(getExternalFilesDir("builds"), projectDir.name)
                .apply { mkdirs() }
        )

        val tools = (application as CompilerApp).toolPaths

        lifecycleScope.launch {
            val engine = BuildEngine(this@BuildLogActivity, tools) { result ->
                appendLog("${if (result.success) "✓" else "✗"} ${result.step.label} (${result.durationMs}ms)")
                if (result.log.isNotBlank()) appendLog(result.log)
            }
            try {
                val apk = withContext(Dispatchers.IO) { engine.build(project) }
                appendLog("\nBuild succeeded: ${apk.absolutePath}")

                if (outputFolderUri != null) {
                    val copiedUri = withContext(Dispatchers.IO) {
                        copyApkToOutputFolder(apk, outputFolderUri)
                    }
                    if (copiedUri != null) {
                        appendLog("Copied to your chosen folder: ${copiedUri.path}")
                    } else {
                        appendLog("Warning: build succeeded but copying to the chosen folder failed.")
                    }
                }
            } catch (e: BuildException) {
                appendLog("\nBuild failed: ${e.message}\n${e.log}")
            } catch (e: Exception) {
                appendLog("\nUnexpected error: ${e.stackTraceToString()}")
            }
        }
    }

    /** Copies the built APK into a user-picked SAF folder. Returns the new file's URI, or null on failure. */
    private fun copyApkToOutputFolder(apk: File, outputFolderUri: Uri): Uri? {
        return try {
            val folderDoc = DocumentFile.fromTreeUri(this, outputFolderUri) ?: return null
            folderDoc.findFile(apk.name)?.delete()
            val newDoc = folderDoc.createFile("application/vnd.android.package-archive", apk.name)
                ?: return null
            contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            newDoc.uri
        } catch (e: Exception) {
            null
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logBuilder.append(line).append('\n')
            binding.textBuildLog.text = logBuilder.toString()
        }
    }
}
