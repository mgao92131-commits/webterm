plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.transport.websocket"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":transport-api"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.9.0")
    testImplementation(libs.junit)
}
