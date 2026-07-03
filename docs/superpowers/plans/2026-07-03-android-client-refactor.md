# Android 客户端架构重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `android-client` 从单模块 56 文件平铺架构，重构为 12 个 Gradle 模块的分层架构，引入 Hilt DI、Fragment + Navigation，并消除 MainActivity 上帝类。

**Architecture:** 增量渐进式重构，8 个顺序阶段，每阶段结束后应用完整可运行。先打 DI 地基（阶段 1）→ 分包（阶段 2）→ 传输抽象（阶段 3）→ 拆 core 模块（阶段 4）→ 拆 transport 模块（阶段 5）→ Fragment 化 + 拆 feature 模块（阶段 6）→ 拆 MainActivity（阶段 7）→ 测试（阶段 8）。

**Tech Stack:** 纯 Java 17、Android SDK 36、Hilt 2.51、OkHttp 4.12、WebRTC 144.7559、AndroidX Navigation 2.7.7 / Lifecycle 2.7 / Fragment 1.6.2、termux terminal-emulator + terminal-view。

## Global Constraints

- 纯 Java 项目，禁用 Kotlin；Hilt 用 `annotationProcessor`（非 kapt）
- `compileSdk = 36`，`minSdk = 23`，`targetSdk = 36`，`sourceCompatibility/targetCompatibility = VERSION_17`
- 所有 `AlertDialog` 必须 `setCanceledOnTouchOutside(false)`（项目既有约定）
- 每阶段独立提交，提交信息前缀 `refactor:` / `feat:` / `test:`
- 不得改变功能行为，重构后所有原有交互保持一致
- Gradle 模块间无循环依赖
- 命名：core 模块包名 `com.webterm.core.<name>`，feature 模块包名 `com.webterm.feature.<name>`，transport 模块包名 `com.webterm.transport.<name>`
- 依赖版本统一进 `gradle/libs.versions.toml`，不再硬编码

**参考设计文档：** `docs/superpowers/specs/2026-07-03-android-client-architecture-refactor-design.md`

---

## 文件结构总览

```
android-client/
├── settings.gradle.kts           ← 注册 12 个新模块
├── gradle/libs.versions.toml     ← 统一版本目录
├── :app/
│   ├── build.gradle.kts          ← 依赖所有 feature + transport 实现模块
│   └── src/main/java/com/webterm/mobile/
│       ├── WebTermApplication.java    ← @HiltAndroidApp
│       ├── MainActivity.java          ← 薄壳，~100 行
│       ├── di/AppModule.java          ← Hilt @Provides
│       ├── di/DefaultTransportFactory.java
│       └── recovery/NetworkRecoveryController.java
├── :core:api/        com.webterm.core.api      WebTermApi, WebTermUrls
├── :core:config/     com.webterm.core.config   ServerConfig, ServerConfigStore, ServerConfigManager
├── :core:cache/      com.webterm.core.cache    TerminalCacheCoordinator, TerminalDiskCache, ...
├── :core:session/    com.webterm.core.session  MuxSession, RelayMuxSessionManager, ...
├── :core:relay/      com.webterm.core.relay    RelayService
├── :transport:api/   com.webterm.transport.api MuxTransport, TransportFactory
├── :transport:websocket/  com.webterm.transport.websocket  WebSocketMuxTransport
├── :transport:webrtc/     com.webterm.transport.webrtc   P2PConnectionManager, ...
├── :feature:home/    com.webterm.feature.home    HomeFragment, HomeViewModel, ...
├── :feature:terminal/ com.webterm.feature.terminal TerminalFragment, TerminalViewModel, ...
├── :feature:relay/   com.webterm.feature.relay   RelayFragment, RelayViewModel, ...
├── :feature:settings/ com.webterm.feature.settings SettingsFragment, SettingsViewModel
├── :terminal-emulator/  ← 已有
└── :terminal-view/      ← 已有
```

---

# 阶段 1：引入 Hilt DI

**目标：** 用 Hilt 替代 `AppContainer`，不动业务逻辑。

## Task 1.1: 配置 Hilt 依赖

**Files:**
- Modify: `android-client/gradle/libs.versions.toml`
- Modify: `android-client/build.gradle.kts`
- Modify: `android-client/app/build.gradle.kts`

- [ ] **Step 1: 创建/完善版本目录**

确认 `android-client/gradle/libs.versions.toml` 含以下条目（不存在则创建）：

```toml
[versions]
agp = "8.5.0"
hilt = "2.51"
okhttp = "4.12.0"
webrtc = "144.7559.09"
navigation = "2.7.7"
lifecycle = "2.7.0"
fragment = "1.6.2"
recyclerview = "1.4.0"
annotation = "1.9.0"
junit = "4.13.2"
mockwebserver = "4.12.0"
orgJson = "20240303"

[libraries]
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
webrtc = { module = "io.github.webrtc-sdk:android", version.ref = "webrtc" }
navigation-fragment = { module = "androidx.navigation:navigation-fragment", version.ref = "navigation" }
lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel", version.ref = "lifecycle" }
lifecycle-livedata = { module = "androidx.lifecycle:lifecycle-livedata", version.ref = "lifecycle" }
fragment = { module = "androidx.fragment:fragment", version.ref = "fragment" }
recyclerview = { module = "androidx.recyclerview:recyclerview", version.ref = "recyclerview" }
annotation = { module = "androidx.annotation:annotation", version.ref = "annotation" }
junit = { module = "junit:junit", version.ref = "junit" }
orgJson = { module = "org.json:json", version.ref = "orgJson" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 2: 顶层 build.gradle.kts 声明 Hilt 插件**

`android-client/build.gradle.kts`：
```kotlin
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 3: app 模块应用 Hilt 插件 + 依赖**

`android-client/app/build.gradle.kts` 的 `plugins` 块加 `alias(libs.plugins.hilt)`；`dependencies` 块用 catalog 别名替换硬编码，并加 Hilt：
```kotlin
dependencies {
  implementation(project(":terminal-view"))
  implementation(libs.okhttp)
  implementation(libs.webrtc)
  implementation(libs.annotation)
  implementation(libs.recyclerview)
  implementation(libs.hilt.android)
  annotationProcessor(libs.hilt.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.orgJson)
}
```

- [ ] **Step 4: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（Hilt 插件应用但尚无 @HiltAndroidApp，仅验证依赖解析）

- [ ] **Step 5: 提交**

```bash
git add android-client/gradle/libs.versions.toml android-client/build.gradle.kts android-client/app/build.gradle.kts
git commit -m "build: 引入 Hilt 依赖与版本目录"
```

## Task 1.2: 改造 WebTermApplication 为 Hilt 入口

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/WebTermApplication.java`

- [ ] **Step 1: 加 @HiltAndroidApp 注解**

读取现有 `WebTermApplication.java`，在类声明上加 `@HiltAndroidApp`。**暂保留** `AppContainer` 字段和 `onCreate` 中的构造逻辑（下一任务逐步迁移后再删）。新增 import：
```java
import dagger.hilt.android.HiltAndroidApp;
```

类声明改为：
```java
@HiltAndroidApp
public class WebTermApplication extends Application {
    // 保留既有 AppContainer 字段和 onCreate 逻辑，后续任务迁移
}
```

- [ ] **Step 2: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/WebTermApplication.java
git commit -m "refactor: WebTermApplication 标注 @HiltAndroidApp"
```

## Task 1.3: 创建 AppModule（@Provides）

**Files:**
- Create: `android-client/app/src/main/java/com/webterm/mobile/di/AppModule.java`

**Interfaces:**
- Produces: Hilt 图提供 `OkHttpClient`、`Handler`、`WebTermApi`、`RelayMuxSessionRegistry`、`ServerConfigStore`、`ServerConfigManager`、`TerminalCacheCoordinator` 单例

- [ ] **Step 1: 新建 AppModule**

`android-client/app/src/main/java/com/webterm/mobile/di/AppModule.java`：
```java
package com.webterm.mobile.di;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.webterm.mobile.RelayMuxSessionRegistry;
import com.webterm.mobile.ServerConfigManager;
import com.webterm.mobile.ServerConfigStore;
import com.webterm.mobile.TerminalCacheCoordinator;
import com.webterm.mobile.WebTermApi;

import java.io.File;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides @Singleton
    static OkHttpClient provideHttpClient() { return new OkHttpClient(); }

    @Provides @Singleton
    static Handler provideMainHandler() { return new Handler(Looper.getMainLooper()); }

    @Provides @Singleton
    static WebTermApi provideWebTermApi(OkHttpClient http) { return new WebTermApi(http); }

    @Provides @Singleton
    static RelayMuxSessionRegistry provideRegistry(OkHttpClient http, Handler mainHandler) {
        return new RelayMuxSessionRegistry(http, mainHandler);
    }

    @Provides @Singleton
    static ServerConfigStore provideConfigStore(@ApplicationContext Context context) {
        return new ServerConfigStore(context);
    }

    @Provides @Singleton
    static ServerConfigManager provideConfigManager(ServerConfigStore store) {
        return new ServerConfigManager(store);
    }

    @Provides @Singleton
    static TerminalCacheCoordinator provideTerminalCache(@ApplicationContext Context context) {
        return new TerminalCacheCoordinator(context.getFilesDir());
    }
}
```

新增 import `javax.inject.Singleton`（如需）。注意 `@Provides static` 方法所在 Module 用 `class`（非 abstract）。

- [ ] **Step 2: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（AppContainer 仍在，Hilt 图与 AppContainer 并存，不冲突）

- [ ] **Step 3: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/di/AppModule.java
git commit -m "feat: 新建 Hilt AppModule 提供共享单例"
```

## Task 1.4: A 类改为构造器 @Inject

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/WebTermApi.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/RelayMuxSessionRegistry.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/SessionRepository.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/SessionCommandController.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/HomeRefreshScheduler.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/ServerSessionMonitor.java`

**Interfaces:**
- Consumes: AppModule 提供的依赖
- Produces: 上述类可被 Hilt 自动注入

- [ ] **Step 1: 各 A 类构造器加 @Inject + @Singleton**

对每个 A 类：
1. 类声明加 `@Singleton`（import `javax.inject.Singleton`）
2. 构造器加 `@Inject`（import `javax.inject.Inject`）
3. 构造器改为 `public`（原为包级）

示例 `WebTermApi.java`：
```java
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
final class WebTermApi {
    @Inject
    public WebTermApi(OkHttpClient http) { this.http = http; }
}
```

对 `RelayMuxSessionRegistry`：构造器已是 `(OkHttpClient, Handler)`，加 `@Inject @Singleton`，改 public。**注意**：此阶段**暂保留** `setTransportProvider` 方法（阶段 3 才删除），因为它被 MainActivity 调用。

对 `SessionRepository`：构造器 `(WebTermApi, TerminalCacheCoordinator, Executor)`——Executor 非 Hilt 提供。改为：保留双构造器，Hilt 注入用的构造器需可解析。检查 `SessionRepository` 是否有 Executor 来源。若无，本阶段先把 Executor 用 `java.util.concurrent.Executors.newSingleThreadExecutor()` 在构造器内创建，或用 Hilt `@Provides` 提供一个默认 Executor。

在 `AppModule` 补：
```java
@Provides @Singleton
static java.util.concurrent.Executor provideIoExecutor() {
    return java.util.concurrent.Executors.newSingleThreadExecutor();
}
```
`SessionRepository` 的 Hilt 构造器改为 `(WebTermApi, TerminalCacheCoordinator, Executor)` 全注入。

对 `ServerSessionMonitor`：构造器 `(RelayMuxSessionRegistry)`，加 `@Inject @Singleton`。

对 `HomeRefreshScheduler`：构造器 `(Handler)`，加 `@Inject @Singleton`。

对 `SessionCommandController`：构造器 `(WebTermApi)`，加 `@Inject @Singleton`。

- [ ] **Step 2: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 冒烟测试**

Run: `cd android-client && ./gradlew :app:installDebug`，启动 app，验证登录、会话列表、终端打开正常。
Expected: 功能与重构前一致。

- [ ] **Step 4: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/
git commit -m "refactor: A 类改为 Hilt 构造器注入"
```

## Task 1.5: B 类改为 @AssistedInject

**Files:**
- Modify: `TerminalLifecycleController.java`, `HomeServerCoordinator.java`, `ServerGroupController.java`, `TerminalConnection.java`, `TerminalClipboardController.java`, `TerminalTitleSynchronizer.java`

**Interfaces:**
- Produces: 各 B 类的 `@AssistedFactory` 接口，供 MainActivity/Fragment 注入

- [ ] **Step 1: 每个 B 类加 @AssistedInject + @AssistedFactory**

对每个 B 类：
1. 构造器加 `@AssistedInject`（import `dagger.assisted.AssistedInject`）
2. 运行时参数加 `@Assisted`（import `dagger.assisted.Assisted`）
3. Hilt 可提供的参数保留无注解
4. 新建嵌套 `@AssistedFactory` 接口（import `dagger.assisted.AssistedFactory`）

示例 `TerminalClipboardController.java`（构造器仅 Activity）：
```java
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

final class TerminalClipboardController {
    private final Activity activity;

    @AssistedInject
    TerminalClipboardController(@Assisted Activity activity) {
        this.activity = activity;
    }

    @AssistedFactory
    interface Factory {
        TerminalClipboardController create(Activity activity);
    }
}
```

示例 `TerminalConnection.java`（构造器 `RelayMuxSessionRegistry` + `Listener`）：
```java
final class TerminalConnection {
    private final RelayMuxSessionRegistry registry;
    private final Listener listener;

    @AssistedInject
    TerminalConnection(RelayMuxSessionRegistry registry, @Assisted Listener listener) {
        this.registry = registry;
        this.listener = listener;
    }

    @AssistedFactory
    interface Factory {
        TerminalConnection create(Listener listener);
    }
}
```

对 `TerminalLifecycleController`：Hilt 参数 = `TerminalCacheCoordinator`、`TerminalConnection`；@Assisted 参数 = `Activity`、`Host`、`TerminalRuntimeState`、`AtomicBoolean`。Factory.create 签名含 4 个 @Assisted 参数。

对 `HomeServerCoordinator`：@Assisted = `SessionRecyclerAdapter`；Hilt 参数 = 其余。Factory.create(adapter)。

对 `ServerGroupController`：@Assisted = `SessionRowHelper`、`TerminalCacheScope`；Hilt 参数 = `RelayMuxSessionRegistry`。Factory.create(helper, scope)。

> `@AssistedFactory` 接口由 Dagger 自动绑定，**不要**在 AppModule 写 @Binds 或 @Provides。

- [ ] **Step 2: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/
git commit -m "refactor: B 类改为 @AssistedInject + @AssistedFactory"
```

## Task 1.6: MainActivity 改 @AndroidEntryPoint + 注入

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`

- [ ] **Step 1: 加 @AndroidEntryPoint，注入工厂和 A 类**

`MainActivity` 类声明加 `@AndroidEntryPoint`（import `dagger.hilt.android.AndroidEntryPoint`）。

把 `onCreate` 中手动 `new XxxController(...)` 替换为字段注入。新增字段：
```java
@Inject WebTermApi api;
@Inject RelayMuxSessionRegistry relayMuxRegistry;
@Inject TerminalCacheCoordinator terminalCache;
@Inject ServerConfigStore configStore;
@Inject ServerConfigManager serverConfigs;
@Inject HomeRefreshScheduler homeRefreshScheduler;
@Inject ServerSessionMonitor serverSessionMonitor;
@Inject SessionCommandController sessionCommands;
@Inject SessionRepository sessionRepository;

@Inject TerminalLifecycleController.Factory terminalLifecycleFactory;
@Inject HomeServerCoordinator.Factory homeServerFactory;
@Inject ServerGroupController.Factory serverGroupFactory;
@Inject TerminalConnection.Factory terminalConnectionFactory;
@Inject TerminalClipboardController.Factory terminalClipboardFactory;
@Inject TerminalTitleSynchronizer.Factory terminalTitleFactory;
```

`onCreate` 中把 `new TerminalLifecycleController(this, this, state, closed, ...)` 改为 `terminalLifecycleFactory.create(this, this, state, closed)`，依此类推。**保留** `relayMuxRegistry.setTransportProvider(...)` 调用（阶段 3 删除）。

- [ ] **Step 2: 删除 mApp 字段及 AppContainer 引用**

删除 `private WebTermApplication mApp;` 及所有 `mApp.xxx()` 调用，改为注入字段。`mApp.relayMuxRegistry()` → `relayMuxRegistry`，`mApp.api()` → `api`，等。

- [ ] **Step 3: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 冒烟测试**

Run: `cd android-client && ./gradlew :app:installDebug`，启动 app 走完登录→会话列表→打开终端→输入命令全流程。
Expected: 全流程正常。

- [ ] **Step 5: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/MainActivity.java
git commit -m "refactor: MainActivity 改用 Hilt 注入"
```

## Task 1.7: 删除 AppContainer

**Files:**
- Delete: `android-client/app/src/main/java/com/webterm/mobile/AppContainer.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/WebTermApplication.java`

- [ ] **Step 1: 确认无 AppContainer 引用**

Run: `cd android-client && grep -rn "AppContainer" app/src/main/java/`
Expected: 仅 `AppContainer.java` 自身和 `WebTermApplication.java` 命中

- [ ] **Step 2: 删除 AppContainer，清理 WebTermApplication**

删除 `AppContainer.java`。`WebTermApplication` 中删除 `appContainer` 字段及 `onCreate` 中的构造，删除 `onTerminate` 中 `appContainer.shutdown()` 调用。保留 `CrashReporter.init(this)` 等非 DI 逻辑。`shutdown` 逻辑（`relayMuxRegistry.shutdown()`、`http.dispatcher().cancelAll()`）迁移到 `WebTermApplication.onTerminate` 直接调用注入的 `RelayMuxSessionRegistry`——但 Application 不被 Hilt 注入。改为：在 `onTerminate` 用 `EntryPointAccessors` 取单例：
```java
@Override
public void onTerminate() {
    super.onTerminate();
    RelayMuxSessionRegistry registry = EntryPointAccessors.fromApplication(
        this, RelayMuxSessionRegistryEntryPoint.class).get();
    registry.shutdown();
}
```
或更简单：给 `RelayMuxSessionRegistry` 加 `@PreDestroy` 不便（Hilt Singleton 无生命周期回调）。实用做法：在 `MainActivity.onDestroy` 调 `registry.shutdown()`。本步采用 MainActivity.onDestroy 调用 shutdown。

- [ ] **Step 3: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/
git commit -m "refactor: 删除 AppContainer，Hilt 完全接管依赖"
```

---

# 阶段 2：按层分包

**目标：** 在 `:app` 内将 56 文件分到子包，只移动文件 + 改 import + 跨包类改 public。

## Task 2.1: 创建子包并移动文件

**Files:** 56 个 Java 文件移动到下述子包

- [ ] **Step 1: 按下表移动文件**

| 目标包 | 文件 |
|--------|------|
| `di` | `AppModule`（已在） |
| `data/api` | `WebTermApi`, `WebTermUrls` |
| `data/config` | `ServerConfig`, `ServerConfigStore`, `ServerConfigManager` |
| `data/cache` | `TerminalCacheCoordinator`, `TerminalDiskCache`, `TerminalCacheScope`, `CachedTerminal`, `CachedSessionMapper` |
| `data/repository` | `SessionRepository` |
| `domain/session` | `MuxSession`, `MuxTransport`, `RelayMuxSessionManager`, `RelayMuxSessionRegistry`, `SessionIdentity`, `WebTermProtocol` |
| `domain/relay` | `RelayCoordinator` |
| `domain/server` | `HomeServerCoordinator`, `ServerGroupController`, `ServerSessionMonitor`, `HomeRefreshScheduler` |
| `domain/terminal` | `TerminalConnection`, `TerminalLifecycleController`, `TerminalRuntimeState`, `TerminalLaunchState`, `TerminalClipboardController`, `TerminalTitleSynchronizer` |
| `domain/command` | `SessionCommandController` |
| `transport` | `WebSocketMuxTransport`, `WebRtcDataChannelTransport`, `P2PConnectionManager`, `P2PDataChannelEndpoint` |
| `ui` | `MainActivity` |
| `ui/home` | `HomeScreenBuilder`, `SessionRecyclerAdapter`, `SessionRowHelper`, `SessionRowActions`, `SessionListItemViews`, `StatusIndicatorView` |
| `ui/terminal` | `TerminalScreenBuilder`, `TerminalConnectionStatusView`, `WebTermTerminalViewClient`, `WebTermTerminalSessionClient`, `TerminalWindowInsetsController` |
| `ui/relay` | `RelayLoginScreenBuilder`, `RelayDevicesScreenBuilder` |
| `ui/dialog` | `ServerConfigDialogHelper`, `RenameSessionDialogHelper`, `SettingsDialogHelper` |
| `ui/common` | `DesignTokens`, `UIUtils`, `PageTransitionAnimator` |
| `recovery` | `NetworkRecoveryController` |
| 根包 | `WebTermApplication`, `CrashReporter` |

移动 = 创建新包目录 + `git mv` 文件 + 更新文件内 `package` 声明。

- [ ] **Step 2: 更新所有 import**

每个文件的 `package` 行改为新包；所有引用该类的文件加 `import com.webterm.mobile.<sub>.<Class>;`。

- [ ] **Step 3: 跨包类改 public**

所有跨包引用的类声明从 `final class X` 改为 `public final class X`（或 `public class X`），其跨包访问的构造器/方法改 `public`。同包内引用的保持包级。

- [ ] **Step 4: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 冒烟测试**

Run: `cd android-client && ./gradlew :app:installDebug`，全流程验证。
Expected: 功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/
git commit -m "refactor: 按层分包 data/domain/transport/ui/di/recovery"
```

---

# 阶段 3：传输接口抽象（前置）

**目标：** `MuxSession`/`RelayMuxSessionManager` 只依赖 `MuxTransport` + `TransportFactory` 接口；消除 P2P 循环依赖。

## Task 3.1: 创建 TransportFactory 接口

**Files:**
- Create: `app/src/main/java/com/webterm/mobile/domain/session/TransportFactory.java`

**Interfaces:**
- Produces: `TransportFactory` 接口（阶段 4 移入 `:transport:api`）

- [ ] **Step 1: 新建接口**

```java
package com.webterm.mobile.domain.session;

interface TransportFactory {
    /** 创建 WebSocket 传输，永不为 null */
    MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId);
    /** 创建 WebRTC DataChannel 传输，P2P 不可用时返回 null */
    MuxTransport createDataChannel(String deviceId);
}
```

- [ ] **Step 2: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/domain/session/TransportFactory.java
git commit -m "feat: 新建 TransportFactory 接口"
```

## Task 3.2: 重构 MuxSession 和 RelayMuxSessionManager 依赖接口

**Files:**
- Modify: `domain/session/MuxSession.java`
- Modify: `domain/session/RelayMuxSessionManager.java`

- [ ] **Step 1: 删除对具体传输类的直接引用**

打开 `MuxSession.java` 和 `RelayMuxSessionManager.java`，找出所有 `new WebSocketMuxTransport(...)` 和 `new WebRtcDataChannelTransport(...)` 调用，以及对应的 import。

把这些构造点替换为通过 `TransportFactory` 创建：构造器接收 `TransportFactory factory`，内部 `factory.createWebSocket(...)` / `factory.createDataChannel(...)`。

若 `MuxSession` 当前在内部 new 传输，改为构造器接收已创建的 `MuxTransport`（由 `RelayMuxSessionManager` 通过 factory 创建后传入）。

- [ ] **Step 2: 删除对 WebSocketMuxTransport/WebRtcDataChannelTransport 的 import**

确认两个文件不再 import 具体传输类。

- [ ] **Step 3: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: 编译失败——因为还没有 TransportFactory 的实现。下一任务补。

- [ ] **Step 4: 提交（WIP，下任务补实现）**

暂不提交，与 3.3 合并。

## Task 3.3: 实现 DefaultTransportFactory

**Files:**
- Create: `app/src/main/java/com/webterm/mobile/di/DefaultTransportFactory.java`
- Modify: `transport/P2PConnectionManager.java`

- [ ] **Step 1: P2PConnectionManager 改 A 类 + Provider 打破循环**

`P2PConnectionManager.java`：
1. 类加 `@Singleton`，构造器加 `@Inject`，改 public
2. 构造器参数：`OkHttpClient http`、`javax.inject.Provider<RelayMuxSessionRegistry> registryProvider`
3. 删除原构造器中对 registry 的直接持有（改为 provider）
4. Listener 改 setter：`private volatile Listener listener;` + `public void setListener(Listener l)`
5. `onP2PDisconnected` 等需要 registry 的地方改 `registryProvider.get().reconnectDevice(...)`

```java
@Singleton
public final class P2PConnectionManager {
    private final OkHttpClient http;
    private final Provider<RelayMuxSessionRegistry> registryProvider;
    private volatile Listener listener;

    @Inject
    public P2PConnectionManager(OkHttpClient http,
                                Provider<RelayMuxSessionRegistry> registryProvider) {
        this.http = http;
        this.registryProvider = registryProvider;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    // 既有方法体保留，引用 registry 处改为 registryProvider.get()
}
```

- [ ] **Step 2: 新建 DefaultTransportFactory**

`app/src/main/java/com/webterm/mobile/di/DefaultTransportFactory.java`：
```java
package com.webterm.mobile.di;

import com.webterm.mobile.domain.session.MuxTransport;
import com.webterm.mobile.domain.session.TransportFactory;
import com.webterm.mobile.transport.P2PConnectionManager;
import com.webterm.mobile.transport.WebSocketMuxTransport;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class DefaultTransportFactory implements TransportFactory {
    private final OkHttpClient http;
    private final P2PConnectionManager p2p;

    @Inject
    public DefaultTransportFactory(OkHttpClient http, P2PConnectionManager p2p) {
        this.http = http;
        this.p2p = p2p;
    }

    @Override
    public MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId) {
        return new WebSocketMuxTransport(http, baseUrl, cookie, sessionId);
    }

    @Override
    public MuxTransport createDataChannel(String deviceId) {
        return p2p.getDataChannelTransport(deviceId);
    }
}
```
import `okhttp3.OkHttpClient`。

- [ ] **Step 3: AppModule 提供 TransportFactory 绑定**

`AppModule.java` 补 `@Binds`——但 `@Provides static` 与 `@Binds abstract` 不能同 class。改用 `@Provides`：
```java
@Provides @Singleton
static TransportFactory provideTransportFactory(DefaultTransportFactory impl) {
    return impl;
}
```
加到 `AppModule`（仍是 `class`）。

- [ ] **Step 4: RelayMuxSessionRegistry 构造器注入 TransportFactory，删除 setTransportProvider**

`RelayMuxSessionRegistry.java`：
1. 构造器从 `(OkHttpClient, Handler)` 改为 `(OkHttpClient, Handler, TransportFactory)`
2. 删除 `setTransportProvider(...)` 方法及 `transportProvider` 字段
3. `forDevice(...)` 中 `() -> transportProvider` 改为直接传 `transportFactory`

- [ ] **Step 5: MainActivity 删除 setTransportProvider 调用，改为注入 P2P + 设置 Listener**

`MainActivity`：
1. 删除 `relayMuxRegistry.setTransportProvider(...)` 那段 lambda
2. `@Inject P2PConnectionManager p2pManager;`
3. `onCreate` 末尾 `p2pManager.setListener(new P2PConnectionManager.Listener() { ... 既有回调 ... });`

- [ ] **Step 6: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 冒烟测试**

Run: `cd android-client && ./gradlew :app:installDebug`，验证 WebSocket 会话 + P2P 会话（若可测）。
Expected: 功能不变

- [ ] **Step 8: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/
git commit -m "refactor: TransportFactory 抽象，消除 P2P 循环依赖"
```

---

# 阶段 4：拆分 core 模块（含 :transport:api）

**目标：** 创建 6 个 Gradle library 模块：`:transport:api`、`:core:api`、`:core:config`、`:core:cache`、`:core:session`、`:core:relay`。先拆 RelayCoordinator。

## Task 4.0a: 创建 :transport:api 模块

**Files:**
- Create: `transport-api/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Move: `domain/session/MuxTransport.java`、`TransportFactory.java` → `:transport:api`

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 末尾加：
```kotlin
include(":transport:api")
```

- [ ] **Step 2: 新建 transport-api/build.gradle.kts**

`android-client/transport-api/build.gradle.kts`：
```kotlin
plugins {
    alias(libs.plugins.android.application)  // 临时：library 插件待 AGP 配置
}
```
> 注意：AGP library 插件需 `com.android.library`。在 `libs.versions.toml` 的 `[plugins]` 补 `android-library = { id = "com.android.library", version.ref = "agp" }`。然后：
```kotlin
plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.webterm.transport.api"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 3: 移动接口文件**

`mkdir -p android-client/transport-api/src/main/java/com/webterm/transport/api`

`git mv` 两个接口文件到该目录，改 `package com.webterm.transport.api;`。把 `MuxTransport` 和 `TransportFactory` 改 `public interface`。

- [ ] **Step 4: :app 依赖 :transport:api**

`app/build.gradle.kts` dependencies 加 `implementation(project(":transport:api"))`。更新 `:app` 中所有 `import com.webterm.mobile.domain.session.MuxTransport` 为 `import com.webterm.transport.api.MuxTransport`（TransportFactory 同理）。

- [ ] **Step 5: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/transport-api/ android-client/app/build.gradle.kts android-client/app/src/
git commit -m "feat: 拆出 :transport:api 接口模块"
```

## Task 4.0b: 拆分 RelayCoordinator 为 RelayService + RelayUiState

**Files:**
- Create: `domain/relay/RelayService.java`
- Create: `ui/relay/RelayUiState.java`
- Delete: `domain/relay/RelayCoordinator.java`

- [ ] **Step 1: 新建 RelayService（纯逻辑）**

`domain/relay/RelayService.java`：把 `RelayCoordinator` 中所有非 UI 逻辑迁入——HTTP 轮询、cookie 刷新、login/register/verifyOtp/deleteDevice/deleteTrustedDevice。暴露 LiveData：
```java
package com.webterm.mobile.domain.relay;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
// ... imports

@Singleton
public class RelayService {
    public enum RelayState { IDLE, LOADING, ONLINE, OFFLINE, ERROR, AUTH_EXPIRED, AUTH_REQUIRED }

    private final MutableLiveData<RelayState> relayState = new MutableLiveData<>(RelayState.IDLE);
    private final MutableLiveData<List<RelayDevice>> devices = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> statusText = new MutableLiveData<>("");
    private final MutableLiveData<Integer> onlineCount = new MutableLiveData<>(0);

    public LiveData<RelayState> relayState() { return relayState; }
    public LiveData<List<RelayDevice>> devices() { return devices; }
    public LiveData<String> statusText() { return statusText; }
    public LiveData<Integer> onlineCount() { return onlineCount; }

    @Inject
    public RelayService(WebTermApi api, ServerConfigManager configManager, Handler mainHandler) { ... }

    public void start(ServerConfig masterConfig) { /* 轮询 */ }
    public void stop() { /* 停止 */ }
    public void refresh() { ... }
    public void login(String email, String password) { ... }
    public void register(String email, String username, String password) { ... }
    public void verifyOtp(String code, String targetDeviceId) { ... }
    public void deleteDevice(String deviceId) { ... }
    public void deleteTrustedDevice(String trustedDeviceId) { ... }

    public interface AuthCallback {
        void onLoginSuccess(String baseUrl, String cookie);
        void onOtpRequired(String targetDeviceId, String cookie);
        void onError(String message);
    }
    public void setAuthCallback(AuthCallback callback) { ... }
}
```
> 完整方法体从 `RelayCoordinator` 原样搬运，仅把直接操作 View 的代码（subtitle.setText、statusDot.setColor）改为更新 MutableLiveData。

- [ ] **Step 2: 新建 RelayUiState（实现 Host 接口，观察 RelayService）**

`ui/relay/RelayUiState.java`：实现 `RelayLoginScreenBuilder.Host`、`RelayDevicesScreenBuilder.Host`。持有 `RelayService`、subtitle View、status dot View。observe RelayService 的 LiveData 驱动 UI。把 `RelayCoordinator` 中 UI 相关代码搬入。

- [ ] **Step 3: 删除 RelayCoordinator**

`git rm domain/relay/RelayCoordinator.java`。更新 MainActivity：原 `RelayCoordinator` 字段改为 `RelayService` + `RelayUiState`，注入 `RelayService`，`RelayUiState` 由工厂创建（含 Activity/View 参数，B 类）。

- [ ] **Step 4: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
验证中继登录、设备列表、删除设备。
Expected: 功能不变

- [ ] **Step 5: 提交**

```bash
git add android-client/app/src/
git commit -m "refactor: 拆分 RelayCoordinator 为 RelayService + RelayUiState"
```

## Task 4.1: 创建 :core:api 模块

**Files:**
- Create: `core-api/build.gradle.kts`
- Move: `data/api/WebTermApi.java`, `WebTermUrls.java`

- [ ] **Step 1: 注册 + build 文件**

`settings.gradle.kts` 加 `include(":core:api")`。`core-api/build.gradle.kts`：
```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.core.api"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.okhttp)
    implementation(libs.annotation)
}
```

- [ ] **Step 2: 移动文件，改包名**

`mkdir -p core-api/src/main/java/com/webterm/core/api`，`git mv` 两个文件，改 `package com.webterm.core.api;`，类改 public。

- [ ] **Step 3: :app 依赖 :core:api，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":core:api"))`。全局替换 `import com.webterm.mobile.data.api.` → `import com.webterm.core.api.`。`WebTermApi` 的内部回调接口（`LoginCallback` 等）改 public。

- [ ] **Step 4: 构建验证**

Run: `cd android-client && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add android-client/settings.gradle.kts android-client/core-api/ android-client/app/
git commit -m "feat: 拆出 :core:api 模块"
```

## Task 4.2: 创建 :core:config 模块

**Files:**
- Create: `core-config/build.gradle.kts`
- Move: `data/config/ServerConfig.java`, `ServerConfigStore.java`, `ServerConfigManager.java` → `com.webterm.core.config`

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 加 `include(":core:config")`。

- [ ] **Step 2: 新建 core-config/build.gradle.kts**

```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.core.config"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.annotation)
}
```

- [ ] **Step 3: 移动文件，改包名**

`mkdir -p core-config/src/main/java/com/webterm/core/config`，`git mv` 3 个文件，改 `package com.webterm.core.config;`，类改 public。`ServerConfigStore` 构造器的 `Context` 参数不变（Hilt `@ApplicationContext` 跨模块仍可注入）。

- [ ] **Step 4: :app 依赖 :core:config，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":core:config"))`。全局替换 `import com.webterm.mobile.data.config.` → `import com.webterm.core.config.`。

- [ ] **Step 5: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL，功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/core-config/ android-client/app/
git commit -m "feat: 拆出 :core:config 模块"
```

## Task 4.3: 创建 :core:cache 模块

**Files:**
- Create: `core-cache/build.gradle.kts`
- Move: `data/cache/TerminalCacheCoordinator.java`, `TerminalDiskCache.java`, `TerminalCacheScope.java`, `CachedTerminal.java`, `CachedSessionMapper.java` → `com.webterm.core.cache`

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 加 `include(":core:cache")`。

- [ ] **Step 2: 新建 core-cache/build.gradle.kts**

```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.core.cache"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":terminal-emulator"))
    implementation(libs.annotation)
}
```

- [ ] **Step 3: 移动 5 个文件，改包名，类改 public**

`mkdir -p core-cache/src/main/java/com/webterm/core/cache`，`git mv` 5 个文件，改 `package com.webterm.core.cache;`。

- [ ] **Step 4: :app 依赖 :core:cache，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":core:cache"))`。全局替换 `import com.webterm.mobile.data.cache.` → `import com.webterm.core.cache.`。`TerminalDiskCache.Metadata` 等内部类若跨模块引用改 public。

- [ ] **Step 5: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
验证终端缓存恢复（打开终端→返回→再打开，历史输出仍在）。
Expected: 功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/core-cache/ android-client/app/
git commit -m "feat: 拆出 :core:cache 模块"
```

## Task 4.4: 创建 :core:session 模块

**Files:**
- Create: `core-session/build.gradle.kts`
- Move: `domain/session/MuxSession.java`, `RelayMuxSessionManager.java`, `RelayMuxSessionRegistry.java`, `SessionIdentity.java`, `WebTermProtocol.java` → `com.webterm.core.session`

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 加 `include(":core:session")`。

- [ ] **Step 2: 新建 core-session/build.gradle.kts**

```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.core.session"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core:api"))
    implementation(project(":transport:api"))
    implementation(libs.okhttp)
    implementation(libs.annotation)
}
```

- [ ] **Step 3: 移动 5 个文件，改包名，类改 public**

`mkdir -p core-session/src/main/java/com/webterm/core/session`，`git mv` 5 个文件，改 `package com.webterm.core.session;`。`RelayMuxSessionRegistry implements ReconnectTrigger`（阶段 5.2 才加，本任务暂不加）。

- [ ] **Step 4: :app 依赖 :core:session，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":core:session"))`。全局替换 `import com.webterm.mobile.domain.session.` → `import com.webterm.core.session.`。

- [ ] **Step 5: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
验证会话列表刷新、打开终端。
Expected: 功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/core-session/ android-client/app/
git commit -m "feat: 拆出 :core:session 模块"
```

## Task 4.5: 创建 :core:relay 模块

**Files:**
- Create: `core-relay/build.gradle.kts`
- Move: `domain/relay/RelayService.java` → `com.webterm.core.relay`（`RelayUiState` 留 `:app`）

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 加 `include(":core:relay")`。

- [ ] **Step 2: 新建 core-relay/build.gradle.kts**

```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.core.relay"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core:api"))
    implementation(project(":core:config"))
    implementation(libs.okhttp)
    implementation(libs.lifecycle.livedata)
    implementation(libs.annotation)
}
```

- [ ] **Step 3: 移动 RelayService，改包名，类改 public**

`mkdir -p core-relay/src/main/java/com/webterm/core/relay`，`git mv RelayService.java`，改 `package com.webterm.core.relay;`。确认无 `android.view.*` import。

- [ ] **Step 4: :app 依赖 :core:relay，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":core:relay"))`。`RelayUiState`（`:app`）的 `import com.webterm.mobile.domain.relay.RelayService` → `import com.webterm.core.relay.RelayService`。

- [ ] **Step 5: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
验证中继登录、设备列表、删除设备。
Expected: 功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/core-relay/ android-client/app/
git commit -m "feat: 拆出 :core:relay 模块"
```

## Task 4.6: 阶段 4 整体冒烟 + 模块图校验

- [ ] **Step 1: 全模块构建**

Run: `cd android-client && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 冒烟测试**

Run: `cd android-client && ./gradlew :app:installDebug`，全流程验证。
Expected: 功能不变

- [ ] **Step 3: 校验模块依赖无环**

Run: `cd android-client && ./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep "com.webterm"`
人工检查无循环。Expected: 无循环

- [ ] **Step 4: 提交（若前面未提交的修正）**

```bash
git add -A
git commit -m "refactor: 阶段 4 完成，6 个 core/transport:api 模块独立"
```

---

# 阶段 5：拆分 transport 实现模块

**目标：** 创建 `:transport:websocket`、`:transport:webrtc`，`DefaultTransportFactory` 留 `:app`。

## Task 5.1: 创建 :transport:websocket

**Files:**
- Create: `transport-websocket/build.gradle.kts`
- Move: `transport/WebSocketMuxTransport.java` → `com.webterm.transport.websocket`

- [ ] **Step 1: 注册模块**

`settings.gradle.kts` 加 `include(":transport:websocket")`。

- [ ] **Step 2: 新建 transport-websocket/build.gradle.kts**

```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.webterm.transport.websocket"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":transport:api"))
    implementation(libs.okhttp)
    implementation(libs.annotation)
}
```

- [ ] **Step 3: 移动文件，改包名，类改 public**

`mkdir -p transport-websocket/src/main/java/com/webterm/transport/websocket`，`git mv WebSocketMuxTransport.java`，改 `package com.webterm.transport.websocket;`，类声明改 `public final class`。

- [ ] **Step 4: :app 依赖 :transport:websocket，更新 import**

`app/build.gradle.kts` 加 `implementation(project(":transport:websocket"))`。`DefaultTransportFactory` 的 `import com.webterm.mobile.transport.WebSocketMuxTransport` → `import com.webterm.transport.websocket.WebSocketMuxTransport`。

- [ ] **Step 5: 构建验证 + 冒烟**

Run: `cd android-client && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
验证 WebSocket 会话打开。
Expected: 功能不变

- [ ] **Step 6: 提交**

```bash
git add android-client/settings.gradle.kts android-client/transport-websocket/ android-client/app/
git commit -m "feat: 拆出 :transport:websocket 模块"
```

## Task 5.2: 创建 :transport:webrtc

文件：`P2PConnectionManager`, `P2PDataChannelEndpoint`, `WebRtcDataChannelTransport` → `com.webterm.transport.webrtc`。依赖：`implementation(project(":transport:api"))`、`implementation(project(":core:api"))`、`libs.webrtc`。类改 public。

注意：`P2PConnectionManager` 构造器注入 `Provider<RelayMuxSessionRegistry>`——但 `:transport:webrtc` 不能依赖 `:core:session`（否则循环：core:session → transport:api，transport:webrtc → core:session）。**解法**：把 `P2PConnectionManager` 对 registry 的依赖抽象为接口 `ReconnectTrigger`（放 `:transport:api`），`RelayMuxSessionRegistry` 实现它（`:core:session` 实现接口，无反向依赖）。`P2PConnectionManager` 注入 `Provider<ReconnectTrigger>`。

`transport-api/ReconnectTrigger.java`：
```java
package com.webterm.transport.api;
public interface ReconnectTrigger {
    void reconnectDevice(String deviceId, String reason);
}
```
`RelayMuxSessionRegistry implements ReconnectTrigger`（`reconnectDevice` 方法已存在，加 implements 即可）。`AppModule` 补 `@Provides` 绑定 `ReconnectTrigger` 到 registry。

- [ ] **Step 1: 新建 ReconnectTrigger 接口**（`:transport:api`）
- [ ] **Step 2: RelayMuxSessionRegistry implements ReconnectTrigger**
- [ ] **Step 3: P2PConnectionManager 改注入 Provider<ReconnectTrigger>**
- [ ] **Step 4: 移动 3 个文件到 :transport:webrtc**
- [ ] **Step 5: 构建验证 + 提交** `feat: 拆出 :transport:webrtc，引入 ReconnectTrigger 接口`

## Task 5.3: DefaultTransportFactory 留 :app，更新依赖

`DefaultTransportFactory` 在 `:app/di/`，依赖 `:transport:websocket` + `:transport:webrtc`。`app/build.gradle.kts` 加这两个依赖。确认 `createWebSocket`/`createDataChannel` 中的 `new WebSocketMuxTransport(...)`、`p2p.getDataChannelTransport(...)` import 正确。

- [ ] **Step 1: 更新 import 和 app 依赖**
- [ ] **Step 2: 构建验证 + 冒烟**
- [ ] **Step 3: 提交** `refactor: DefaultTransportFactory 装配两个 transport 实现`

---

# 阶段 6：拆分 feature 模块（6a Fragment 化 / 6b Gradle 拆分）

## 阶段 6a：在 :app 内 Fragment 化

### Task 6a.1: 引入 Navigation/Fragment/Lifecycle 依赖

`app/build.gradle.kts` 加：
```kotlin
implementation(libs.navigation.fragment)
implementation(libs.lifecycle.viewmodel)
implementation(libs.lifecycle.livedata)
implementation(libs.fragment)
```
- [ ] **Step 1: 加依赖**
- [ ] **Step 2: 构建验证** `./gradlew :app:assembleDebug`
- [ ] **Step 3: 提交** `build: 引入 Navigation/Fragment/Lifecycle 依赖`

### Task 6a.2: 创建 nav_graph.xml 和 activity_main 布局

**Files:**
- Create: `app/src/main/res/navigation/nav_graph.xml`
- Create: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: activity_main.xml 仅含 NavHostFragment**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/nav_host"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:defaultNavHost="true"
        app:navGraph="@navigation/nav_graph"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

若 `androidx.constraintlayout` 未引入，加 `implementation("androidx.constraintlayout:constraintlayout:2.1.4")` 到 catalog。

- [ ] **Step 2: nav_graph.xml 含 4 个 destination**

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/homeFragment">

    <fragment android:id="@+id/homeFragment"
        android:name="com.webterm.mobile.ui.home.HomeFragment"
        android:label="Home">
        <action android:id="@+id/action_home_to_terminal"
            app:destination="@id/terminalFragment"/>
        <action android:id="@+id/action_home_to_relay"
            app:destination="@id/relayFragment"/>
        <action android:id="@+id/action_home_to_settings"
            app:destination="@id/settingsFragment"/>
    </fragment>

    <fragment android:id="@+id/terminalFragment"
        android:name="com.webterm.mobile.ui.terminal.TerminalFragment"
        android:label="Terminal"/>

    <fragment android:id="@+id/relayFragment"
        android:name="com.webterm.mobile.ui.relay.RelayFragment"
        android:label="Relay"/>

    <fragment android:id="@+id/settingsFragment"
        android:name="com.webterm.mobile.ui.settings.SettingsFragment"
        android:label="Settings"/>
</navigation>
```

- [ ] **Step 3: 提交** `feat: 新建 nav_graph 和 activity_main 布局`

### Task 6a.3: 创建 HomeFragment + HomeViewModel

**Files:**
- Create: `ui/home/HomeFragment.java`
- Create: `ui/home/HomeViewModel.java`

- [ ] **Step 1: HomeViewModel 持有 HomeServerCoordinator，暴露 LiveData**

```java
package com.webterm.mobile.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import com.webterm.mobile.domain.server.HomeServerCoordinator;
// ...
public class HomeViewModel extends ViewModel {
    private final HomeServerCoordinator coordinator;
    public HomeViewModel(HomeServerCoordinator coordinator) { this.coordinator = coordinator; }
    public LiveData<List<SessionRow>> sessions() { return coordinator.sessions(); }
    public void addServer(ServerConfig cfg) { coordinator.addServer(cfg); }
    public void removeServer(String url) { coordinator.removeServer(url); }
    public void refresh() { coordinator.refresh(); }
}
```

- [ ] **Step 2: HomeFragment 替代 MainActivity 的首页逻辑**

`HomeFragment` 继承 `Fragment`，`@AndroidEntryPoint`。`onCreateView` 用 `HomeScreenBuilder` 构建视图。把 `MainActivity` 中 `showSessionHome()` 相关逻辑、swipe-to-delete、`SessionRowActions` 实现搬入 Fragment。`SessionRowActions` 回调改为调用 `HomeViewModel` 方法 + `findNavController().navigate()`。

- [ ] **Step 3: 构建验证 + 冒烟（首页）**
- [ ] **Step 4: 提交** `feat: 新建 HomeFragment + HomeViewModel`

### Task 6a.4: 创建 TerminalFragment + TerminalViewModel

同 6a.3 模式。`TerminalViewModel` 持有 `TerminalLifecycleController`（通过工厂）、`TerminalConnection`，暴露连接状态、终端输出 LiveData。`TerminalFragment` 实现 `WebTermTerminalViewClient.Host`、`WebTermTerminalSessionClient.Host`、`TerminalLifecycleController.Host`。

**跨 Fragment 传参**：`nav_graph` 的 terminalFragment 加 `<argument>` for `sessionId` + `baseUrl`。`HomeFragment` 导航时传：
```java
Bundle args = new Bundle();
args.putString("sessionId", sessionId);
args.putString("baseUrl", baseUrl);
findNavController().navigate(R.id.action_home_to_terminal, args);
```
`TerminalViewModel` 用 sessionId 从 `SessionRepository`/`TerminalCacheCoordinator` 查完整元数据。

- [ ] **Step 1: ViewModel + Fragment**
- [ ] **Step 2: 跨 Fragment 传参**
- [ ] **Step 3: 构建验证 + 冒烟（打开终端、输入命令、返回）**
- [ ] **Step 4: 提交** `feat: 新建 TerminalFragment + TerminalViewModel`

### Task 6a.5: 创建 RelayFragment + RelayViewModel

`RelayViewModel` 接手 `RelayUiState`，注入 `RelayService`，observe 其 LiveData。`RelayFragment` 实现 `RelayLoginScreenBuilder.Host`、`RelayDevicesScreenBuilder.Host`。

- [ ] **Step 1-3: 同上**
- [ ] **Step 4: 提交** `feat: 新建 RelayFragment + RelayViewModel`

### Task 6a.6: 创建 SettingsFragment + SettingsViewModel

- [ ] **Step 1-3: 同上**
- [ ] **Step 4: 提交** `feat: 新建 SettingsFragment + SettingsViewModel`

### Task 6a.7: NetworkRecoveryController 改 App 级单例 + LiveData

`NetworkRecoveryController` 改 `@Singleton @Inject`，构造器注入 `Context`、`Handler`。把 `Host` 回调改为 `MutableLiveData<Void> networkRecovered`，`onAvailable` 中 `networkRecovered.postValue(null)`。各 ViewModel observe 它。

- [ ] **Step 1: 改造 NetworkRecoveryController**
- [ ] **Step 2: 各 ViewModel observe networkRecovered**
- [ ] **Step 3: 构建验证 + 冒烟（断网恢复重连）**
- [ ] **Step 4: 提交** `refactor: NetworkRecoveryController 改 LiveData 单例`

### Task 6a.8: 阶段 6a 冒烟验收

- [ ] **Step 1: 全流程冒烟**

验证：登录→会话列表→打开终端→输入命令→返回→横竖屏旋转（终端会话不丢）→后台恢复（断网重连）→中继设备管理→设置。

- [ ] **Step 2: 提交** `refactor: 阶段 6a Fragment 化完成`

## 阶段 6b：拆为独立 Gradle 模块

### Task 6b.1: 资源前缀去重

- [ ] **Step 1: 全局检索重名资源**

Run: `cd android-client && find app/src/main/res -name "*.xml" | xargs grep -l "name=" | sort`
人工比对 layout/drawable/string 名称，重名按模块加前缀 `home_`/`terminal_`/`relay_`/`settings_`。

- [ ] **Step 2: 重命名资源 + 更新 R 引用**
- [ ] **Step 3: 构建验证** `./gradlew :app:assembleDebug`
- [ ] **Step 4: 提交** `refactor: 资源按 feature 加前缀`

### Task 6b.2-6b.5: 拆 4 个 feature 模块

对每个 feature（home/terminal/relay/settings）重复：
1. `settings.gradle.kts` 加 `include(":feature:<name>")`
2. 新建 `feature-<name>/build.gradle.kts`，依赖对应 `:core:*` 和 `:transport:api`（terminal 额外依赖 `:terminal-view`、`:core:cache`）
3. `git mv` Fragment/ViewModel/Builder/Adapter 等到 `com.webterm.feature.<name>`
4. `git mv` 对应 `res/layout/<prefix>_*.xml`、drawable 到 feature 模块 `src/main/res/`
5. `:app` 加 `implementation(project(":feature:<name>"))`
6. 更新 import、R 类引用（feature 模块内用本模块 R，跨模块用聚合 R）
7. `nav_graph.xml` 的 `android:name` 改为 `com.webterm.feature.<name>.<Name>Fragment`
8. 构建验证 + 冒烟
9. 提交 `feat: 拆出 :feature:<name> 模块`

每个 feature 一个独立提交。

### Task 6b.6: 阶段 6b 整体验收

- [ ] **Step 1: 全模块构建** `./gradlew assembleDebug`
- [ ] **Step 2: 全流程冒烟**
- [ ] **Step 3: 提交** `refactor: 阶段 6b 4 个 feature 模块独立`

---

# 阶段 7：拆解 MainActivity

**目标：** MainActivity 降至 ~100 行。

## Task 7.1: MainActivity 仅保留壳职责

**Files:**
- Modify: `MainActivity.java`

- [ ] **Step 1: 删除已迁移逻辑**

删除：所有 `showXxx()` 导航方法（改 `findNavController().navigate()`）、swipe-to-delete、子系统创建（已 Hilt 注入）、后台 detach 定时器（已入 TerminalViewModel）、崩溃日志分享（入 `CrashReporter` 独立方法）、10 个 Host 接口实现（已入各 Fragment）。

- [ ] **Step 2: MainActivity 最终形态**

```java
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    public void onBackPressed() {
        // 终端页拦截返回：弹出确认退出弹窗
        NavController nav = Navigation.findNavController(this, R.id.nav_host);
        if (nav.getCurrentDestination().getId() == R.id.terminalFragment) {
            showConfirmExitDialog();
        } else {
            super.onBackPressed();
        }
    }

    private void showConfirmExitDialog() { /* 既有弹窗逻辑，setCanceledOnTouchOutside(false) */ }
}
```

- [ ] **Step 3: 构建验证 + 全流程冒烟**
- [ ] **Step 4: 行数检查**

Run: `wc -l android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`
Expected: ≤ 120 行

- [ ] **Step 5: 提交** `refactor: MainActivity 拆解为薄壳`

---

# 阶段 8：测试验证

## Task 8.1: core 模块单元测试

**Files:**
- Create: 各 core 模块 `src/test/java/...`

- [ ] **Step 1: :core:api 测试（mockwebserver）**

`core-api/src/test/java/com/webterm/core/api/WebTermApiTest.java`：
```java
package com.webterm.core.api;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebTermApiTest {
    private MockWebServer server;
    private WebTermApi api;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        api = new WebTermApi(new OkHttpClient());
    }

    @After
    public void tearDown() throws Exception { server.shutdown(); }

    @Test
    public void login_成功_返回合并后的cookie() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"otp_required\":false}")
            .addHeader("Set-Cookie", "session=abc123; Path=/"));
        api.login(server.url("/").toString(), null, "user", "pass",
            new WebTermApi.LoginCallback() {
                public void onReady(String baseUrl, String cookie) {
                    assertTrue(cookie.contains("session=abc123"));
                }
                public void onError(String message) { fail(message); }
            });
    }
}
```
`core-api/build.gradle.kts` 加 `testImplementation(libs.junit)`、`testImplementation(libs.mockwebserver)`。

- [ ] **Step 2: 运行测试**

Run: `cd android-client && ./gradlew :core:api:test`
Expected: PASS

- [ ] **Step 3: 提交** `test: :core:api WebTermApi 测试`

- [ ] **Step 4: :core:config 测试** —— `ServerConfigStore` 序列化往返（用 Robolectric 或 SharedPreferences mock）。提交 `test: :core:config 测试`

- [ ] **Step 5: :core:cache 测试** —— `TerminalDiskCache` 读写、过期清理（用临时目录）。提交 `test: :core:cache 测试`

- [ ] **Step 6: :core:session 测试** —— `SessionIdentity.normalizePart`、`cacheKey` 纯函数测试。提交 `test: :core:session 测试`

- [ ] **Step 7: :core:relay 测试** —— `RelayService` 401 重试链（mockwebserver）。提交 `test: :core:relay 测试`

## Task 8.2: Hilt 图装配测试

**Files:**
- Create: `app/src/androidTest/java/com/webterm/mobile/HiltGraphTest.java`

- [ ] **Step 1: 测试类**

```java
package com.webterm.mobile;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dagger.hilt.android.testing.HiltAndroidRule;
import dagger.hilt.android.testing.HiltAndroidTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@HiltAndroidTest
@RunWith(AndroidJUnit4.class)
public class HiltGraphTest {
    @Rule public HiltAndroidRule hiltRule = new HiltAndroidRule(this);

    @Test
    public void 所有单例可注入() {
        // 验证 Hilt 图可装配
    }
}
```
`app/build.gradle.kts` 加 `androidTestImplementation(libs.hilt.android.testing)`、`androidTestImplementation("androidx.test.ext:junit:1.1.5")`、`androidTestAnnotationProcessor(libs.hilt.compiler)`。

- [ ] **Step 2: 运行** `./gradlew :app:connectedDebugAndroidTest`（需连接设备/模拟器）
- [ ] **Step 3: 提交** `test: Hilt 图装配测试`

## Task 8.3: 最终全量冒烟 + 文档更新

- [ ] **Step 1: 全量构建 + 测试**

Run: `cd android-client && ./gradlew assembleDebug test`
Expected: 全部 PASS

- [ ] **Step 2: 全流程真机冒烟**

登录、会话列表、打开终端、输入命令、P2P（若可）、断网重连、横竖屏、后台恢复、中继管理、设置。

- [ ] **Step 3: 更新 README**

`android-client/README.md` 补充新模块结构说明。

- [ ] **Step 4: 提交** `docs: 更新 README 模块结构`

---

## 自检清单

实施完成后逐项确认：

- [ ] 12 个新 Gradle 模块全部独立编译
- [ ] 模块间无循环依赖（`./gradlew dependencies` 校验）
- [ ] `AppContainer` 已删除
- [ ] MainActivity ≤ 120 行
- [ ] 10 个 Host 接口全部消除（迁移到 Fragment/ViewModel）
- [ ] P2P 循环依赖消除（无 null 检查、无 setTransportProvider）
- [ ] `RelayService` 无 `android.view.*` import
- [ ] 每个 core 模块至少 1 个单元测试通过
- [ ] Hilt 图装配测试通过
- [ ] 所有 AlertDialog `setCanceledOnTouchOutside(false)`
- [ ] 全流程功能与重构前一致
