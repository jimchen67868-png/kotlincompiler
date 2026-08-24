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

    // Native command-line tool binaries (d8, aapt2, zipalign) go in
    // app/src/main/jniLibs/<abi>/ so Android's package manager extracts
    // them alongside the app at install time with correct exec permissions.
    // See README.md for how to obtain these binaries.
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// ---------------------------------------------------------------------
// Compiler-asset preparation
//
// Previously these had to be hand-downloaded and dexed manually (e.g. in
// Termux). Instead, we resolve them as normal Gradle dependencies (which
// benefits from Gradle's dependency cache + retries, far more reliable
// than a bare wget), dex the compiler jar using the d8 that already ships
// with the Android SDK build-tools installed in CI, and copy the SDK's
// own android.jar — all automatically, every build.
// ---------------------------------------------------------------------

val kotlinCompilerJarConfig by configurations.creating {
    isTransitive = false
}
val kotlinStdlibJarConfig by configurations.creating {
    isTransitive = false
}

dependencies {
    kotlinCompilerJarConfig("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")
    kotlinStdlibJarConfig("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}

/**
 * kotlin-compiler-embeddable.jar (and d8.jar) are large enough that d8
 * splits its output across classes.dex, classes2.dex, classes3.dex, ...
 * (the standard Android multidex convention) once the 64K-method limit
 * for a single dex file is exceeded. DexClassLoader can load all of them
 * together *if* they're packaged into one zip with those exact entry
 * names — same as how a multidex APK works — so we zip everything d8
 * produced instead of only keeping the first classes.dex (which silently
 * drops classes and causes ClassNotFoundException at runtime).
 */
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
    description = "Downloads the Kotlin compiler + stdlib, dexes the compiler with d8, " +
        "and copies the SDK's android.jar into src/main/assets/tools/"

    val assetsDir = file("src/main/assets/tools")
    val dexOutDir = layout.buildDirectory.dir("compilerDex").get().asFile

    val compilerJarProvider = kotlinCompilerJarConfig.elements.map { it.single().asFile }
    val stdlibJarProvider = kotlinStdlibJarConfig.elements.map { it.single().asFile }

    val sdkDir = android.sdkDirectory
    val d8Script = File(sdkDir, "build-tools/${android.buildToolsVersion}/d8")
    val d8LibJar = File(sdkDir, "build-tools/${android.buildToolsVersion}/lib/d8.jar")
    val platformAndroidJar = File(sdkDir, "platforms/android-${android.compileSdk}/android.jar")

    inputs.files(kotlinCompilerJarConfig, kotlinStdlibJarConfig)
    outputs.file(File(assetsDir, "kotlin-compiler-dex.jar"))
    outputs.file(File(assetsDir, "kotlin-stdlib-dex.jar"))
    outputs.file(File(assetsDir, "kotlin-stdlib.jar"))
    outputs.file(File(assetsDir, "android.jar"))
    outputs.file(File(assetsDir, "d8-dex.jar"))

    doLast {
        check(d8Script.exists()) {
            "d8 not found at ${d8Script.absolutePath}. Make sure build-tools " +
                "${android.buildToolsVersion} is installed (sdkmanager \"build-tools;${android.buildToolsVersion}\")."
        }
        check(d8LibJar.exists()) {
            "d8.jar not found at ${d8LibJar.absolutePath} (needed to dex d8 itself so it can " +
                "run in-process on-device). Build-tools layout may have changed."
        }
        check(platformAndroidJar.exists()) {
            "android.jar not found at ${platformAndroidJar.absolutePath}. Make sure " +
                "platforms;android-${android.compileSdk} is installed."
        }

        assetsDir.mkdirs()
        dexOutDir.deleteRecursively()
        dexOutDir.mkdirs()

        // Dex the Kotlin compiler itself.
        exec {
            commandLine(
                d8Script.absolutePath,
                "--min-api", "26",
                "--output", dexOutDir.absolutePath,
                compilerJarProvider.get().absolutePath
            )
        }
        zipDexFiles(dexOutDir, File(assetsDir, "kotlin-compiler-dex.jar"))

        // The compiler itself is written in Kotlin, so its own DexClassLoader
        // needs kotlin-stdlib classes (e.g. kotlin.jvm.internal.Intrinsics)
        // available at runtime — separately from the plain kotlin-stdlib.jar
        // we also ship as a -classpath argument for compiling the *user's*
        // source. Dex a copy for the compiler's own runtime.
        val stdlibDexOutDir = layout.buildDirectory.dir("stdlibDex").get().asFile
        stdlibDexOutDir.deleteRecursively()
        stdlibDexOutDir.mkdirs()
        exec {
            commandLine(
                d8Script.absolutePath,
                "--min-api", "26",
                "--output", stdlibDexOutDir.absolutePath,
                stdlibJarProvider.get().absolutePath
            )
        }
        zipDexFiles(stdlibDexOutDir, File(assetsDir, "kotlin-stdlib-dex.jar"))

        // Dex d8 itself (it's pure JVM bytecode) so it too can run in-process
        // on-device via DexClassLoader instead of needing a native ARM binary.
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

// ---------------------------------------------------------------------
// Native aapt2 binary
//
// aapt2 is C++, not JVM bytecode, so unlike d8/kotlinc it can't be dexed
// and run in-process — it needs a real ARM binary. Termux's aapt2 package
// dynamically links against several Termux-prefix shared libraries
// (libprotobuf, libc++_shared, etc.) that won't resolve from inside a
// different app's sandbox, so we pull from AndroidIDE's androidide-tools
// releases instead, which are built specifically to run standalone inside
// a sandboxed Android app (exactly our situation).
// ---------------------------------------------------------------------

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
            ?: throw GradleException(
                "Could not find an 'aapt2' binary inside the downloaded archive from " +
                    "$downloadUrl. The AndroidIDE release layout may have changed — check " +
                    "https://github.com/AndroidIDEOfficial/androidide-tools/releases manually " +
                    "and update the toolsVersion / path logic in this task."
            )

        aapt2Binary.copyTo(aapt2Out, overwrite = true)
        aapt2Out.setExecutable(true, false)

        println("aapt2 ready at ${aapt2Out.absolutePath}")
    }
}

// Hook into every variant's asset-merge and native-lib-merge steps so
// everything exists before packaging, regardless of AGP task-name specifics
// across versions (hence the broad name matching).
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(prepareCompilerAssets) }
    tasks.matching { it.name.contains("NativeLibs") || it.name.contains("JniLibFolders") }
        .configureEach { dependsOn(fetchNativeBuildTools) }
}
