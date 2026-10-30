// The design tokens of docs/design/tokens.json as Compose values, and the theme that applies them.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.geunal.designsystem"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Versions are pinned on purpose and Dependabot proposes the moves; a release upstream must not turn CI red.
        disable += "AndroidGradlePluginVersion"
        disable += "GradleDependency"
        // targetSdk follows the Play requirement of the year, not the newest release (docs/parts/android.md §1).
        disable += "OldTargetApi"
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui)
    // ToastCenter's timer.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // DesignTokensTest reads docs/design/tokens.json.
    testImplementation(libs.kotlinx.serialization.json)
}

dependencyLocking {
    lockAllConfigurations()
}
