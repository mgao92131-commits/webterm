plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.terminal.runtime"
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
    implementation(project(":core-contract"))
    implementation(project(":core-session"))
    api(project(":terminal-model"))
    implementation(project(":terminal-protocol"))
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation("androidx.annotation:annotation:1.9.0")

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.12.0")
}

// 性能基线测试通过 stdout 打印结构化报告。
tasks.withType<Test>().configureEach {
    testLogging.showStandardStreams = true
}
