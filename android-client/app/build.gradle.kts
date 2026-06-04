plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.webterm.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.webterm.mobile"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = false
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

dependencies {
  implementation(project(":terminal-view"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.annotation:annotation:1.9.0")
}
