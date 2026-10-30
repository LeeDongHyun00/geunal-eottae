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
        // Kakao publishes its Android SDK only to its own repository. Only the SDK's group is looked up there, and that
        // group only there, so nothing else can ever resolve from it.
        exclusiveContent {
            forRepository {
                maven("https://devrepo.kakao.com/nexus/content/groups/public/") { name = "Kakao" }
            }
            filter { includeGroup("com.kakao.sdk") }
        }
    }
}

rootProject.name = "geunal"

// docs/parts/android.md §2. The remaining feature modules and the widget arrive with the screens they hold.
include(":core", ":bridge", ":designsystem", ":feature-calendar", ":feature-account", ":app")
