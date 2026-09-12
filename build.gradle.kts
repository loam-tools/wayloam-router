plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "tools.loam.wayloam.router"
    version = "0.1.0-SNAPSHOT"
}
