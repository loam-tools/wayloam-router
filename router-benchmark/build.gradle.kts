plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("tools.loam.wayloam.router.benchmark.BenchmarkMainKt")
}

dependencies {
    implementation(project(":router-api"))
    implementation(project(":router-core"))
    implementation(project(":router-data"))
    implementation(project(":router-brouter"))
    implementation(project(":router-runtime"))
    implementation(project(":router-http"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}

