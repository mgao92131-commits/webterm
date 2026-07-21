plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.webterm.core.config"
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
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.12.0")
    // 本地 JVM 单元测试中 Android 的 org.json 是被桩化的（调用即抛异常），
    // 引入真实 org.json 以便对 ServerConfig 的序列化做往返测试。
    testImplementation("org.json:json:20240303")
}
