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
    named("test") {
        java.srcDir("../vendor/brouter/brouter-map-creator/src/main/java")
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("wayloam.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

dependencies {
    api(project(":router-api"))
    implementation(project(":router-core"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")
}
