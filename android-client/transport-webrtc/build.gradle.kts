plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.transport.webrtc"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    implementation(project(":transport-api"))
    implementation(project(":core-api"))
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.12.0")
}
