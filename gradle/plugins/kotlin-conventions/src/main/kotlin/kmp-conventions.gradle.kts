import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin-conventions")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = the<LibrariesForLibs>()

kotlin {
    jvm()

    android {
        compileSdk = libs.versions.compile.sdk.get().toInt()
        minSdk = libs.versions.min.sdk.get().toInt()
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.library.bytecode.version.get()))
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Run JVM tests for the fast development loop"
    dependsOn(tasks.named("jvmTest"))
}
