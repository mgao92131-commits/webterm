import java.security.MessageDigest
import java.time.Instant

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.hilt)
}

fun commandBytes(workingDir: File, vararg command: String): ByteArray? = try {
    val execution = providers.exec {
        this.workingDir = workingDir
        commandLine(*command)
        isIgnoreExitValue = true
    }
    if (execution.result.get().exitValue == 0) {
        execution.standardOutput.asBytes.get()
    } else {
        null
    }
} catch (_: Exception) {
    null
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

val sourceRoot = rootProject.projectDir.parentFile
val commitBytes = commandBytes(sourceRoot, "git", "rev-parse", "HEAD")
val buildGitCommit = commitBytes?.toString(Charsets.UTF_8)?.trim()
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "unknown"
val statusBytes = commandBytes(sourceRoot, "git", "status", "--porcelain=v1", "-z")
val buildGitDirty = statusBytes != null && statusBytes.isNotEmpty()
val buildSourceTreeHash = if (buildGitCommit == "unknown") {
    "unknown"
} else if (!buildGitDirty) {
    sha256(buildGitCommit.toByteArray(Charsets.UTF_8))
} else {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(commandBytes(sourceRoot, "git", "diff", "--binary", "HEAD") ?: byteArrayOf())
    digest.update(statusBytes)
    val untracked = commandBytes(
        sourceRoot, "git", "ls-files", "--others", "--exclude-standard", "-z")
        ?: byteArrayOf()
    for (pathBytes in untracked.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }) {
        digest.update(pathBytes.toByteArray(Charsets.UTF_8))
        val source = File(sourceRoot, pathBytes)
        if (source.isFile) digest.update(source.readBytes())
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
val protocolFile = File(sourceRoot, "shared/proto/terminal_screen_v2.proto")
val buildProtocolSchemaHash = if (protocolFile.isFile) sha256(protocolFile.readBytes()) else "unknown"
val buildTimestamp = Instant.now().toString()

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
        buildConfigField("String", "GIT_COMMIT", "\"$buildGitCommit\"")
        buildConfigField("boolean", "GIT_DIRTY", buildGitDirty.toString())
        buildConfigField("String", "SOURCE_TREE_HASH", "\"$buildSourceTreeHash\"")
        buildConfigField("String", "BUILD_TIME_UTC", "\"$buildTimestamp\"")
        buildConfigField("String", "PROTOCOL_SCHEMA_HASH", "\"$buildProtocolSchemaHash\"")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "BUILD_VARIANT_ID", "\"debug\"")
        }
        create("diag") {
            initWith(getByName("release"))
            applicationIdSuffix = ".diag"
            versionNameSuffix = "-diag"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks.add("release")
            buildConfigField("String", "BUILD_VARIANT_ID", "\"diagnostics\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BUILD_VARIANT_ID", "\"release\"")
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
      buildConfig = true
      shaders = false
    }

    testOptions {
      unitTests.isReturnDefaultValues = true
    }

    // 现存 lint 债务单独建档；CI 仍执行 lint，并从此基线起阻止新增问题。
    lint {
      baseline = file("lint-baseline.xml")
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

dependencies {
  implementation(project(":core-contract"))
  implementation(libs.androidx.core.ktx)
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
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
