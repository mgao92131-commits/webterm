plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.ui.common"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = false; aidl = false; buildConfig = false; shaders = false }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    implementation(libs.lifecycle.livedata)
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(project(":core-api"))
    implementation(project(":core-config"))
}
