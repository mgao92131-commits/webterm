plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.webterm.core.api"
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
    api(project(":core-contract"))
    implementation(project(":core-config"))
    implementation(project(":data-http"))
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.12.0")
}
