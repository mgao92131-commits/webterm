plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.webterm.terminal.protocol"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    aidl = false
    buildConfig = false
    shaders = false
  }

  sourceSets["main"].java.srcDirs("src/main/java")
  sourceSets["test"].java.srcDirs("src/test/java")
}

dependencies {
  api(libs.protobuf.java)
  implementation(project(":terminal-model"))
  implementation("androidx.annotation:annotation:1.9.0")
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
