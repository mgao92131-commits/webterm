plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.webterm.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.webterm.mobile.new"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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

    testOptions {
      unitTests.isReturnDefaultValues = true
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
  implementation("io.github.webrtc-sdk:android:144.7559.09")
  implementation("androidx.annotation:annotation:1.9.0")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
