plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.webterm.core.cache"
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
    implementation(project(":core-config"))
    implementation(project(":core-api"))
    implementation(project(":terminal-emulator"))
}
