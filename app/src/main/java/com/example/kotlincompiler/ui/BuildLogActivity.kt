package com.example.kotlincompiler.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
    }

    private lateinit var binding: ActivityBuildLogBinding
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuildLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectDirPath = intent.getStringExtra(EXTRA_PROJECT_DIR) ?: return finish()
        val projectDir = File(projectDirPath)

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
            } catch (e: BuildException) {
                appendLog("\nBuild failed: ${e.message}\n${e.log}")
            } catch (e: Exception) {
                appendLog("\nUnexpected error: ${e.stackTraceToString()}")
            }
        }
    }

    private fun appendLog(line: String) {
        logBuilder.append(line).append('\n')
        binding.textBuildLog.text = logBuilder.toString()
    }
}
