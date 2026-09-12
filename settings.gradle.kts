pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wayloam-router"

include(
    ":router-api",
    ":router-core",
    ":router-data",
    ":router-http",
    ":router-brouter",
    ":router-android",
    ":router-benchmark",
)
