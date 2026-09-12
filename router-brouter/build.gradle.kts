plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    named("main") {
        java.srcDirs(
            "../vendor/brouter/brouter-util/src/main/java",
            "../vendor/brouter/brouter-expressions/src/main/java",
            "../vendor/brouter/brouter-codec/src/main/java",
            "../vendor/brouter/brouter-mapaccess/src/main/java",
            "../vendor/brouter/brouter-core/src/main/java",
        )
        resources.srcDir("../vendor/brouter/misc/profiles2")
    }
}

dependencies {
    api(project(":router-api"))
    implementation(project(":router-core"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
