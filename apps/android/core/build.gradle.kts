// The Rust core as an Android library: the cargo-ndk libraries and the UniFFI-generated Kotlin that make build-android
// writes to core/out/android (core/scripts/build-android.sh), plus JNA, which the generated code loads them through.
plugins {
    alias(libs.plugins.android.library)
}

val coreOut = layout.projectDirectory.dir("../../../core/out/android")
if (!coreOut.dir("kotlin").asFile.isDirectory || !coreOut.dir("jniLibs").asFile.isDirectory) {
    throw GradleException("core/out/android is missing: run `make build-android` first")
}

android {
    namespace = "app.geunal.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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

// The Variant API rather than sourceSets: AGP 9's DSL types no longer let a build script add directories there.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(coreOut.dir("kotlin").asFile.path)
        variant.sources.jniLibs?.addStaticSourceDirectory(coreOut.dir("jniLibs").asFile.path)
    }
}

dependencies {
    api(libs.jna) {
        // JNA publishes the Android build with libjnidispatch.so as an AAR next to the plain jar.
        artifact { type = "aar" }
    }
    // The generated `suspend fun sync()` and the other async methods.
    api(libs.kotlinx.coroutines.core)
    // @RequiresApi on the generated cleaner that exists from API 34.
    implementation(libs.androidx.annotation)
}

dependencyLocking {
    lockAllConfigurations()
}
