import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.conventions.kmp)
    alias(libs.plugins.conventions.linting)
    alias(libs.plugins.conventions.publishing)
    alias(libs.plugins.burst)
}

kotlin {
    explicitApi()

    android {
        namespace = "io.github.charlietap.dexter"
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

configure<PublishingConventionsExtension> {
    name = "dexter"
    description = "Writes Android DEX files from Kotlin"
}
