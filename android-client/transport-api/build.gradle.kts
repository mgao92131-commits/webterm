plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.transport.api"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
