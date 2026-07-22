plugins { alias(libs.plugins.android.library) }

android {
  namespace = "com.webterm.data.http"
  compileSdk = 36
  defaultConfig { minSdk = 23 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":core-contract"))
  implementation(project(":core-config"))
  implementation(libs.hilt.android)
  annotationProcessor(libs.hilt.compiler)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.annotation:annotation:1.9.0")
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
