plugins { `java-library` }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  testImplementation(libs.junit)
}

// 性能基线测试（PerformanceBaselineTest）通过 stdout 打印结构化报告。
tasks.withType<Test>().configureEach {
  testLogging.showStandardStreams = true
}
