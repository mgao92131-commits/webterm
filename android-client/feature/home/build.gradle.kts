plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.feature.home"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = false; aidl = false; buildConfig = false; shaders = false }
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-config"))
    implementation(project(":core-cache"))
    implementation(project(":core-session"))
    implementation(project(":core-relay"))
    implementation(project(":transport-api"))
    implementation(project(":ui-common"))
    implementation(project(":terminal-ui"))
    implementation(project(":feature:relay"))
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
