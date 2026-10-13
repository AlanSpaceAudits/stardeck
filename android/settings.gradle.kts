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
        // Astronomy Engine is published through JitPack only
        maven("https://jitpack.io") { content { includeGroup("io.github.cosinekitty") } }
    }
}
rootProject.name = "stardeck"
include(":app")
