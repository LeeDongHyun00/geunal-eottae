// AGP 9 compiles Kotlin itself and depends on KGP 2.2.10; the newer KGP on the buildscript classpath is the version it
// then uses (AGP 9.0 release notes, "Runtime dependency on Kotlin Gradle plugin"). Every plugin and library version
// is in gradle/libs.versions.toml, and the resolved versions in the lockfiles next to each build file.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}
