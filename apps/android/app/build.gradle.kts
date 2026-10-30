// The app: the root (onboarding or the tabs), the hand-built DI container and the build's configuration.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// The provider keys, the domain and the links come from Gradle properties (~/.gradle/gradle.properties or -P), never
// from the repository (CLAUDE.md "비밀값과 로그"). Without them the app still builds and runs: sign-in with the missing
// provider fails with a toast, and the callback that needs it is left out of the manifest.
fun setting(
    name: String,
    default: String = "",
): String = providers.gradleProperty(name).orElse(default).get()

val kakaoAppKey = setting("geunalKakaoAppKey")
val appleServiceId = setting("geunalAppleServiceId")
val appLinkHost = setting("geunalAppLinkHost")
val apiBase = setting("geunalApiBase")

// Only alpha builds offer the test statistics (m1.md screen 3); a public build passes -PgeunalChannel=public.
val channel = setting("geunalChannel", "alpha")

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
        // Screenshots go through the test storage service, which AGP copies off the device after the run.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        ndk {
            // Only the ABIs the core is built for (core/scripts/build-android.sh); JNA's AAR also carries x86, armeabi
            // and mips, which would install on those devices and crash on the first core call.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "KAKAO_APP_KEY", quoted(kakaoAppKey))
        buildConfigField("String", "APPLE_SERVICE_ID", quoted(appleServiceId))
        buildConfigField("String", "APP_LINK_HOST", quoted(appLinkHost))
        buildConfigField("String", "CHANNEL", quoted(channel))
        buildConfigField("String", "TERMS_URL", quoted(setting("geunalTermsUrl")))
        buildConfigField("String", "PRIVACY_URL", quoted(setting("geunalPrivacyUrl")))
        manifestPlaceholders["geunalKakaoAppKey"] = kakaoAppKey
        manifestPlaceholders["geunalAppLinkHost"] = appLinkHost
    }

    buildTypes {
        debug {
            // The emulator reaches the local stack of `make up` on the host at 10.0.2.2.
            buildConfigField("String", "API_BASE", quoted(apiBase.ifEmpty { "http://10.0.2.2:8080" }))
        }
        release {
            // The production host comes with the domain (docs/kickoff.md §9); until then a release build has none and
            // the core works offline.
            buildConfigField("String", "API_BASE", quoted(apiBase))
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
        disable += "GradleDependency"
        // targetSdk follows the Play requirement of the year, not the newest release (docs/parts/android.md §1).
        disable += "OldTargetApi"
    }
}

androidComponents {
    onVariants { variant ->
        if (kakaoAppKey.isNotEmpty()) variant.sources.manifests.addStaticManifestFile("manifests/kakao-login.xml")
        if (appLinkHost.isNotEmpty()) variant.sources.manifests.addStaticManifestFile("manifests/apple-sign-in.xml")
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":designsystem"))
    implementation(project(":feature-account"))
    implementation(project(":feature-calendar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.storage)
    androidTestUtil(libs.androidx.test.services)
    // The empty activity the screen tests host their composables in; debug builds only.
    debugImplementation(libs.compose.ui.test.manifest)
}

dependencyLocking {
    lockAllConfigurations()
}
