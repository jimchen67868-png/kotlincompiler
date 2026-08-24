import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.kotlincompiler"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.example.kotlincompiler"
        minSdk = 26          // DexClassLoader + subprocess exec behave best on 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Bundling a real compiler + IntelliJ-platform patches (see
        // kotlinc-android dependency below) pushes us well past the 64K
        // method limit for a single dex file.
        multiDexEnabled = true

        // Only fetching a native aapt2 for arm64-v8a; see fetchNativeBuildTools below.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // A real Kotlin compiler (2.0.0) with the IntelliJ-platform internals
    // it embeds patched specifically for ART compatibility.
    // Source: https://github.com/Cosmic-Ide/kotlinc-android
    implementation("com.github.Cosmic-Ide.kotlinc-android:kotlinc:v2.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val kotlinStdlibJarConfig by configurations.creating {
    isTransitive = false
}

dependencies {
    kotlinStdlibJarConfig("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
}

fun zipDexFiles(dexDir: File, outputZip: File) {
    val dexFiles = dexDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
        ?.sortedBy { f ->
            val suffix = f.name.removePrefix("classes").removeSuffix(".dex")
            if (suffix.isEmpty()) 1 else suffix.toInt()
        }
        ?: emptyList()

    check(dexFiles.isNotEmpty()) { "No classesN.dex files found in ${dexDir.absolutePath}" }

    ZipOutputStream(outputZip.outputStream()).use { zos ->
        dexFiles.forEach { dexFile ->
            zos.putNextEntry(ZipEntry(dexFile.name))
            dexFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

val prepareCompilerAssets by tasks.registering {
    group = "kotlincompiler"
    description = "Copies kotlin-stdlib.jar + android.jar, and dexes d8, into assets/tools/"

    val assetsDir = file("src/main/assets/tools")
    val stdlibJarProvider = kotlinStdlibJarConfig.elements.map { it.single().asFile }

    val sdkDir = android.sdkDirectory
    val d8Script = File(sdkDir, "build-tools/${android.buildToolsVersion}/d8")
    val d8LibJar = File(sdkDir, "build-tools/${android.buildToolsVersion}/lib/d8.jar")
    val platformAndroidJar = File(sdkDir, "platforms/android-${android.compileSdk}/android.jar")

    inputs.files(kotlinStdlibJarConfig)
    outputs.file(File(assetsDir, "kotlin-stdlib.jar"))
    outputs.file(File(assetsDir, "android.jar"))
    outputs.file(File(assetsDir, "d8-dex.jar"))

    doLast {
        check(d8Script.exists()) {
            "d8 not found at ${d8Script.absolutePath}. Make sure build-tools " +
                "${android.buildToolsVersion} is installed."
        }
        check(d8LibJar.exists()) {
            "d8.jar not found at ${d8LibJar.absolutePath}."
        }
        check(platformAndroidJar.exists()) {
            "android.jar not found at ${platformAndroidJar.absolutePath}."
        }

        assetsDir.mkdirs()

        val d8DexOutDir = layout.buildDirectory.dir("d8Dex").get().asFile
        d8DexOutDir.deleteRecursively()
        d8DexOutDir.mkdirs()
        exec {
            commandLine(
                d8Script.absolutePath,
                "--min-api", "26",
                "--output", d8DexOutDir.absolutePath,
                d8LibJar.absolutePath
            )
        }
        zipDexFiles(d8DexOutDir, File(assetsDir, "d8-dex.jar"))

        stdlibJarProvider.get().copyTo(File(assetsDir, "kotlin-stdlib.jar"), overwrite = true)
        platformAndroidJar.copyTo(File(assetsDir, "android.jar"), overwrite = true)

        println("Compiler assets ready in ${assetsDir.absolutePath}")
    }
}

val fetchNativeBuildTools by tasks.registering {
    group = "kotlincompiler"
    description = "Downloads a native ARM64 aapt2 binary into jniLibs/arm64-v8a/"

    val toolsVersion = "34.0.4"
    val downloadUrl = "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/" +
        "v$toolsVersion/build-tools-$toolsVersion-aarch64.tar.xz"

    val downloadDir = layout.buildDirectory.dir("nativeBuildTools").get().asFile
    val archiveFile = File(downloadDir, "build-tools-$toolsVersion-aarch64.tar.xz")
    val extractDir = File(downloadDir, "extracted")
    val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
    val aapt2Out = File(jniLibsDir, "libaapt2_bin.so")

    outputs.file(aapt2Out)

    doLast {
        downloadDir.mkdirs()
        jniLibsDir.mkdirs()

        if (!archiveFile.exists()) {
            println("Downloading aapt2 from $downloadUrl")
            URL(downloadUrl).openStream().use { input ->
                archiveFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        extractDir.deleteRecursively()
        extractDir.mkdirs()
        exec {
            commandLine("tar", "-xf", archiveFile.absolutePath, "-C", extractDir.absolutePath)
        }

        val aapt2Binary = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" }
            ?: throw GradleException("Could not find an 'aapt2' binary inside the downloaded archive.")

        aapt2Binary.copyTo(aapt2Out, overwrite = true)
        aapt2Out.setExecutable(true, false)

        println("aapt2 ready at ${aapt2Out.absolutePath}")
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(prepareCompilerAssets) }
    tasks.matching { it.name.contains("NativeLibs") || it.name.contains("JniLibFolders") }
        .configureEach { dependsOn(fetchNativeBuildTools) }
}
