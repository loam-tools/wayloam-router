plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tools.loam.wayloam.router.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":router-api"))
    implementation(project(":router-core"))
    implementation(project(":router-data"))
    implementation(project(":router-brouter"))
    implementation(libs.androidx.core.ktx)
}
