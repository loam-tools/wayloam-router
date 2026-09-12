plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tools.loam.wayloam.router.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":router-api"))
    api(project(":router-runtime"))
    implementation(project(":router-core"))
    implementation(project(":router-data"))
    implementation(project(":router-brouter"))
    implementation(libs.androidx.core.ktx)

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
