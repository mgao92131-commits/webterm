plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.hilt)
}

android {
    namespace = "com.webterm.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.webterm.mobile.c2"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("diag") {
            initWith(getByName("release"))
            applicationIdSuffix = ".diag"
            versionNameSuffix = "-diag"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks.add("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets {
        getByName("debug") {
            java.srcDirs("src/debug/java", "src/diagnostics/java")
        }
        getByName("diag") {
            java.srcDirs("src/diag/java", "src/diagnostics/java")
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
  implementation(project(":core-contract"))
  "debugImplementation"(libs.xlog)
  "diagImplementation"(libs.xlog)
  implementation(project(":transport-api"))
  implementation(project(":transport-websocket"))
  implementation(project(":core-api"))
  implementation(project(":data-http"))
  implementation(project(":core-config"))
  implementation(project(":core-cache"))
  implementation(project(":core-session"))
  implementation(project(":core-relay"))
  implementation(project(":ui-kit"))
  implementation(project(":feature:relay"))
  implementation(project(":feature:terminal"))
  implementation(project(":feature:home"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.annotation:annotation:1.9.0")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation(libs.navigation.fragment)
  implementation(libs.navigation.ui)
  implementation(libs.lifecycle.viewmodel)
  implementation(libs.lifecycle.livedata)
  implementation(libs.fragment.ktx)
  implementation(libs.hilt.android)
  annotationProcessor(libs.hilt.compiler)
  compileOnly("com.google.errorprone:error_prone_annotations:2.23.0")
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
  testImplementation("org.mockito:mockito-core:5.12.0")
  testImplementation("androidx.arch.core:core-testing:2.2.0")
}
