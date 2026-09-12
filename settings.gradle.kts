pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "wayloam-router"

include(
    ":router-api",
    ":router-core",
    ":router-data",
    ":router-http",
    ":router-brouter",
    ":router-runtime",
    ":router-benchmark",
)

// JVM routing, benchmarks and CI can run without an Android SDK.
if (providers.gradleProperty("includeAndroid").orNull != "false") {
    include(":router-android")
}
