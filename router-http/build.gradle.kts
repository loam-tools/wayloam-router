plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":router-data"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
