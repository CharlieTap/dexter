pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    includeBuild("gradle/plugins/kotlin-conventions")
    includeBuild("gradle/plugins/linting-conventions")
    includeBuild("gradle/plugins/publishing-conventions")
    includeBuild("gradle/plugins/versions-conventions")
}

plugins {
    id("com.gradle.develocity") version ("4.5.0")
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { _ -> !System.getenv("GITHUB_ACTIONS").isNullOrEmpty() }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dexter-multiplatform"

include(":dexter")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
