// What the app needs around the core and nothing more (docs/parts/android.md §2): the Keystore-backed SecretStore,
// the change listener as a Flow, and the directories the core's files live in.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.geunal.bridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // The Keystore only exists on a device, so SecretStore's tests are instrumented (make connected-android); what
        // it decides from the SDK level alone runs as a unit test.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

dependencyLocking {
    lockAllConfigurations()
}
