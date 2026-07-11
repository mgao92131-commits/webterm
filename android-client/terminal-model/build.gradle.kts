plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.webterm.terminal.model"
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
  testImplementation(libs.junit)
}
