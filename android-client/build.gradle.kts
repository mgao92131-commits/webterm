// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.hilt) apply false
}

// AGP 的默认 testInstrumentationRunner 是 androidx.test.runner.AndroidJUnitRunner，
// 但没有 androidTest 源码的模块不会引入 runner 依赖，导致 connectedDebugAndroidTest
// 构建出的空测试 APK 在 instrumentation 启动时 ClassNotFoundException 崩溃。
// 统一为所有 Android 模块补上 runner，使空测试模块以 0 tests 通过。
subprojects {
  plugins.withId("com.android.library") {
    dependencies.add("androidTestImplementation", libs.androidx.test.runner.get())
  }
  plugins.withId("com.android.application") {
    dependencies.add("androidTestImplementation", libs.androidx.test.runner.get())
  }
}
