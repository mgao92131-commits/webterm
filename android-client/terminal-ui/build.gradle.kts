plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.webterm.terminal.ui"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = false; aidl = false; buildConfig = false; shaders = false }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":ui-common"))
    implementation("androidx.annotation:annotation:1.9.0")
}
