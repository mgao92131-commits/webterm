plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.webterm.core.session"
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
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(project(":transport-api"))
    implementation(project(":core-api"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.12.0")
}
