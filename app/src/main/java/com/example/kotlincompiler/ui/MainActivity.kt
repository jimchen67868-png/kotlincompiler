package com.example.kotlincompiler.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.kotlincompiler.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedProjectDir: File? = null
    private var outputFolderUri: Uri? = null

    private val pickProjectFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { onProjectFolderPicked(it) }
    }

    private val pickOutputFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { onOutputFolderPicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonPickProject.setOnClickListener {
            pickProjectFolder.launch(null)
        }

        binding.buttonPickOutput.setOnClickListener {
            pickOutputFolder.launch(null)
        }

        binding.buttonBuild.setOnClickListener {
            val dir = selectedProjectDir ?: return@setOnClickListener
            val outputUri = outputFolderUri ?: return@setOnClickListener
            val intent = Intent(this, BuildLogActivity::class.java).apply {
                putExtra(BuildLogActivity.EXTRA_PROJECT_DIR, dir.absolutePath)
                putExtra(BuildLogActivity.EXTRA_OUTPUT_FOLDER_URI, outputUri.toString())
            }
            startActivity(intent)
        }
    }

    private fun onProjectFolderPicked(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        binding.buttonBuild.isEnabled = false
        binding.textSelectedProject.text = "Copying project into app storage…"

        lifecycleScope.launch {
            val destDir = File(filesDir, "imported_project").apply {
                deleteRecursively()
                mkdirs()
            }
            val copiedOk = withContext(Dispatchers.IO) {
                val treeDoc = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                treeDoc?.let { copyDocumentTree(it, destDir) } ?: false
            }

            if (copiedOk) {
                selectedProjectDir = destDir
                binding.textSelectedProject.text = "Project ready: ${destDir.absolutePath}"
                updateBuildButtonEnabled()
            } else {
                binding.textSelectedProject.text = "Failed to copy selected folder. Try again."
                selectedProjectDir = null
                updateBuildButtonEnabled()
            }
        }
    }

    private fun onOutputFolderPicked(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        outputFolderUri = treeUri
        val folderName = DocumentFile.fromTreeUri(this, treeUri)?.name ?: treeUri.toString()
        binding.textSelectedOutput.text = "Output folder: $folderName"
        updateBuildButtonEnabled()
    }

    private fun updateBuildButtonEnabled() {
        binding.buttonBuild.isEnabled = selectedProjectDir != null && outputFolderUri != null
    }

    /** Recursively copies a SAF DocumentFile tree into a real filesystem directory. */
    private fun copyDocumentTree(source: DocumentFile, destDir: File): Boolean {
        destDir.mkdirs()
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                if (!copyDocumentTree(child, File(destDir, name))) return false
            } else {
                try {
                    contentResolver.openInputStream(child.uri)?.use { input ->
                        File(destDir, name).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: return false
                } catch (e: Exception) {
                    return false
                }
            }
        }
        return true
    }
}
