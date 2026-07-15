plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.feature.relay"
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
    implementation(project(":core-relay"))
    implementation(project(":ui-common"))
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.livedata)
    implementation("androidx.annotation:annotation:1.9.0")
}
