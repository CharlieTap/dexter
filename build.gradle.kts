plugins {
    alias(libs.plugins.burst) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.gradle.maven.publish) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false

    alias(libs.plugins.conventions.kmp) apply false
    alias(libs.plugins.conventions.kotlin) apply false
    alias(libs.plugins.conventions.publishing) apply false
    alias(libs.plugins.conventions.linting) apply false
    alias(libs.plugins.conventions.versions)
}
