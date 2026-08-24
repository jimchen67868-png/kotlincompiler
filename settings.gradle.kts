pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // For com.github.Cosmic-Ide.kotlinc-android:kotlinc — a Kotlin
        // compiler build patched for ART compatibility (see app/build.gradle.kts).
        maven("https://jitpack.io")
    }
}

rootProject.name = "KotlinCompilerApp"
include(":app")
