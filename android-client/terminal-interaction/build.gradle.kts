plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.webterm.terminal.interaction"
  compileSdk = 36

  defaultConfig { minSdk = 23 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    aidl = false
    buildConfig = false
    shaders = false
  }
}

dependencies {
  implementation("androidx.annotation:annotation:1.9.0")
}
