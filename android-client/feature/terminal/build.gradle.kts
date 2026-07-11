plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.feature.terminal"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures { compose = false; aidl = false; buildConfig = false; shaders = false }
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-config"))
    implementation(project(":core-cache"))
    implementation(project(":core-session"))
    implementation(project(":transport-api"))
    implementation(project(":ui-common"))
    implementation(project(":terminal-ui"))
    implementation(project(":terminal-view"))
    implementation(project(":terminal-model"))
    implementation(project(":terminal-protocol"))
    implementation(project(":terminal-renderer"))
    implementation("com.squareup.okio:okio:3.6.0")
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.12.0")
}
