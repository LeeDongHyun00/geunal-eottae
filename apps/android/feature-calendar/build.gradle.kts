import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.HostTestBuilder

// The calendar screens (docs/parts/android.md §2): the next-up bar, the month, week and list views and the quick entry
// (docs/design/m1.md screens 4–8).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.geunal.feature.calendar"
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
        // One fake core for the unit tests and the on-device screen tests.
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
        variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)

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
