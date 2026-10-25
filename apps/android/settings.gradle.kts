// Google's repository comes first: AGP, AndroidX and Compose live there, and Maven Central serves the rest (Kotlin,
// JNA, coroutines). The plugin portal only answers for what neither has (the ktlint plugin).
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
    }
}

rootProject.name = "geunal"

// docs/parts/android.md §2. The feature and widget modules arrive with the screens they hold (M1 and later).
include(":core", ":bridge", ":designsystem", ":app")
