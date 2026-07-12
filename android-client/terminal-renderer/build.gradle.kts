plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.webterm.terminal.renderer"
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
}

dependencies {
  implementation(project(":terminal-model"))
  implementation(project(":terminal-interaction"))
  implementation("androidx.annotation:annotation:1.9.0")
  testImplementation(libs.junit)
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:core:1.6.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
}
