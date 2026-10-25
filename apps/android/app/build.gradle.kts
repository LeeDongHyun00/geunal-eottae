// The app: navigation, the hand-built DI container and, in M0, the one screen that shows the core running on the device.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.geunal"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.geunal"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Only the ABIs the core is built for (core/scripts/build-android.sh); JNA's AAR also carries x86, armeabi
            // and mips, which would install on those devices and crash on the first core call.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            // The emulator reaches the local stack of `make up` on the host at 10.0.2.2.
            buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8080\"")
        }
        release {
            // The production host comes with the domain in M1 (docs/kickoff.md §9); the core does no network in M0.
            buildConfigField("String", "API_BASE", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
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
        // targetSdk follows the Play requirement of the year, not the newest release (docs/parts/android.md §1).
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

dependencyLocking {
    lockAllConfigurations()
}
