plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.webterm.core.relay"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(project(":core-api"))
    implementation(project(":data-http"))
    implementation(project(":core-config"))
    implementation(project(":core-session"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
