plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":router-api"))
    api(project(":router-data"))
    api(project(":router-core"))
    implementation(project(":router-brouter"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
