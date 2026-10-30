import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.HostTestBuilder

// Onboarding, sign-in and the recovery code (docs/parts/android.md §2 `feature-account`): screens 1–3, 21 and 24 of
// docs/design/m1.md in M1's first PR, and the account screens under 나 in a later one.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.geunal.feature.account"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Screenshots go through the test storage service, which AGP copies off the device after the run.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
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

androidComponents {
    onVariants { variant ->
        // One set of fakes for the unit tests and the on-device screen tests.
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
        variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":bridge"))
    implementation(project(":designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kakao.user)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.storage)
    androidTestUtil(libs.androidx.test.services)
}

dependencyLocking {
    lockAllConfigurations()
}
