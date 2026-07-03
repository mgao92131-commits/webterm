# Android 客户端架构与模块化重构设计

- 日期：2026-07-03
- 范围：`android-client/` 安卓原生客户端
- 维护者：单人
- 策略：增量渐进式重构（每个阶段结束后功能完整可用，可随时停止或发布）

## 1. 背景

当前 `:app` 模块的 56 个 Java 文件全部平铺在 `com.webterm.mobile` 一个包下，存在以下架构问题：

1. **MainActivity 是上帝类**：798 行，实现 10 个 Host/Listener 接口，持有 17 个协作者字段，管理 6 种屏幕模式，在 `onCreate()` 中手动构造所有子系统。
2. **AppContainer 是服务定位器反模式**：所有共享依赖通过 `mApp.xxx()` 获取，依赖关系不透明，无法单元测试。
3. **没有分层架构**：UI、业务逻辑、网络、数据混在同一个包。
4. **RelayCoordinator 职责过重**：619 行，混合 HTTP 轮询、cookie 刷新、登录/注册/OTP、设备 CRUD 和 8 种 UI 状态管理。
5. **循环初始化依赖**：`relayMuxRegistry` 的 transportProvider 依赖 `P2PConnectionManager`，而 `P2PConnectionManager` 构造又传入 `relayMuxRegistry`，靠 null 检查规避崩溃。
6. **WebTermApi 过大**：719 行，13 个回调接口，混合 API 客户端 + cookie 解析 + 错误解析。
7. **MuxSession/RelayMuxSessionManager 直接依赖传输实现类**，无法干净拆分。

## 2. 目标架构

```
android-client/
├── :app                              ← 薄壳（Application + Hilt + NavGraph + DefaultTransportFactory）
│
├── :core:api                         ← 网络层（WebTermApi + URL 工具）
├── :core:config                      ← 配置持久化（ServerConfig/Store/Manager）
├── :core:cache                       ← 终端缓存
├── :core:session                     ← 多路复用会话（只依赖 :transport:api 接口）
├── :core:relay                       ← 中继纯逻辑（RelayService，无 UI）
│
├── :transport:api                    ← 传输抽象（MuxTransport + TransportFactory 接口）
├── :transport:websocket              ← WebSocket 实现
├── :transport:webrtc                 ← WebRTC P2P 实现
│
├── :feature:home                     ← 首页（会话列表 + 服务器管理）
├── :feature:terminal                 ← 终端（连接 + 渲染 + 快捷键）
├── :feature:relay                    ← 中继 UI（登录/注册/设备管理）
├── :feature:settings                 ← 设置
│
├── :terminal-emulator                ← 已有（C/JNI）
└── :terminal-view                    ← 已有
```

### 分层约束

- `:core:*` 不依赖任何 `:feature:*`，不依赖 Android View 类
- `:feature:*` 之间互不依赖，通过 Navigation Component 导航
- `:transport:*` 上层只依赖 `:transport:api` 接口，实现由 `:app` 中的 `DefaultTransportFactory` 装配
- 所有模块间无循环依赖

## 3. 重构阶段总览

| 阶段 | 内容 | 新建模块 | 风险 | 估时 |
|------|------|----------|------|------|
| 1 | 引入 Hilt DI | 0 | 低 | 2-3 天 |
| 2 | 按层分包 | 0 | 低 | 0.5-1 天 |
| 3 | 传输接口抽象（前置） | 0 | 中 | 1-2 天 |
| 4 | 拆分 core 模块（含 RelayCoordinator 预拆分、:transport:api 接口模块） | 6 | 中 | 3-4 天 |
| 5 | 拆分 transport 实现模块 | 2 | 中 | 1-2 天 |
| 6 | 拆分 feature 模块（6a Fragment 化 / 6b Gradle 拆分） | 4 | 高 | 5-7 天 |
| 7 | 拆解 MainActivity | 0 | 中 | 1-2 天 |
| 8 | 测试验证 | 0 | 低 | 1-2 天 |
| **合计** | | **12** | | **15-23 天** |

> 估时已考虑 Fragment 化与资源迁移的隐藏成本，比初版更保守。

---

## 4. 阶段 1：引入 Hilt DI

**目标**：用 Hilt 替代 `AppContainer` 手动依赖管理，不动业务逻辑。

### 4.1 环境配置

项目是纯 Java（无 Kotlin），使用 `annotationProcessor`（非 kapt）。

`gradle/libs.versions.toml` 统一管理（顺带把现有硬编码版本也收编）：
```toml
[versions]
hilt = "2.51"
[libraries]
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

`app/build.gradle.kts`：
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
}
dependencies {
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)  // 纯 Java 用 annotationProcessor
    // ...
}
```

> 顶层 `build.gradle.kts` 需加 `alias(libs.plugins.hilt) apply false`。

### 4.2 改造 WebTermApplication

```java
@HiltAndroidApp
public class WebTermApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 原 AppContainer 创建逻辑删除，改为 Hilt 自动装配
        // 保留：CrashReporter 初始化等非依赖注入逻辑
        CrashReporter.init(this);
    }

    @Override
    public void onTerminate() {
        // 原 AppContainer.shutdown() 逻辑迁移到各单例的 @PreDestroy 或手动调用
        super.onTerminate();
    }
}
```

### 4.3 改造 AppContainer → Hilt Module

新建 `di/AppModule.java`，将 `AppContainer` 的构造逻辑迁移为 `@Provides`/`@Binds`：
```java
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
    // ServerConfigStore（需 @ApplicationContext Context）、ServerConfigManager、TerminalCacheCoordinator 同理
}
```

`AppContainer.java` 在本阶段末删除。

### 4.4 依赖分类与注入策略

并非所有类都适合纯 `@Inject` 构造器。先按构造函数参数分类：

#### A 类：全自动注入（构造参数全部来自 Hilt 图）

这些类的构造参数全部是 Hilt 可提供的依赖（OkHttpClient、Handler、WebTermApi、其他 A 类对象等）：

| 类 | 构造参数来源 |
|----|-------------|
| `WebTermApi` | `OkHttpClient` |
| `RelayMuxSessionRegistry` | `OkHttpClient`, `Handler`, `TransportFactory`（阶段 3 引入） |
| `SessionRepository` | `WebTermApi`, `TerminalCacheCoordinator` |
| `SessionCommandController` | `WebTermApi` |
| `HomeRefreshScheduler` | `Handler` |
| `ServerSessionMonitor` | `RelayMuxSessionRegistry` |
| `P2PConnectionManager` | `OkHttpClient`, `Provider<RelayMuxSessionRegistry>`（Listener 通过 setter 设置，见 6.5） |
| `DefaultTransportFactory` | `OkHttpClient`, `P2PConnectionManager`（阶段 3 引入） |

> `P2PConnectionManager` 必须是 A 类（@Singleton 进图），否则 `DefaultTransportFactory` 无法通过构造器注入它。其 `Listener`（运行时回调）改用 setter 设置。`Provider<RelayMuxSessionRegistry>` 用于打破 `registry → factory → p2p → registry` 的构造期循环（见 6.5）。

改为：
```java
@Singleton
final class SessionRepository {
    @Inject
    SessionRepository(WebTermApi api, TerminalCacheCoordinator cache) { ... }
}
```

#### B 类：需 Assisted 注入（构造参数含运行时值或 Activity/Fragment 引用）

这些类的构造函数包含 `Activity`、Host 接口回调、View 对象等**非 Hilt 可提供**的参数：

| 类 | 运行时参数 | 策略 |
|----|-----------|------|
| `TerminalLifecycleController` | `Activity`, `Host`, `TerminalRuntimeState`, `AtomicBoolean` | `@AssistedInject` + `@AssistedFactory` |
| `HomeServerCoordinator` | `SessionRecyclerAdapter`（含 View 逻辑） | `@AssistedInject` + `@AssistedFactory` |
| `ServerGroupController` | `RelayMuxSessionRegistry`, `SessionRowHelper`, `TerminalCacheScope` | `@AssistedInject` |
| `TerminalConnection` | `RelayMuxSessionRegistry`, `TerminalConnection.Listener` | `@AssistedInject` |
| `TerminalClipboardController` | `Activity` | `@AssistedInject` |
| `TerminalTitleSynchronizer` | `Activity` | `@AssistedInject` |

Assisted 注入示例：
```java
final class TerminalLifecycleController {
    @AssistedInject
    TerminalLifecycleController(
        TerminalCacheCoordinator cache,        // ← Hilt 提供
        TerminalConnection connection,         // ← Hilt 提供
        @Assisted Activity activity,           // ← 运行时提供
        @Assisted Host host,                   // ← 运行时提供
        @Assisted TerminalRuntimeState state   // ← 运行时提供
    ) { ... }

    @AssistedFactory
    interface Factory {
        TerminalLifecycleController create(Activity activity, Host host, TerminalRuntimeState state);
    }
}
```

> Hilt `@AssistedInject` 需额外依赖：`implementation("com.google.dagger:hilt-android:2.51")` 已包含（2.51+ 内置 assisted-inject）。

#### Assisted 工厂无需注册

`@AssistedFactory` 接口由 Dagger 编译期自动生成实现并绑定到 Hilt 图，**无需在 Module 中写 `@Provides` 或 `@Binds`**。调用方直接注入工厂接口即可。

调用方（MainActivity 或后续 Fragment）注入工厂并传运行时参数：
```java
@Inject TerminalLifecycleController.Factory terminalFactory;
// ...
controller = terminalFactory.create(this, this, terminalState);
```

### 4.5 影响范围

| 文件 | 变更 |
|------|------|
| `AppContainer.java` | 删除 |
| `WebTermApplication.java` | `@HiltAndroidApp`，移除 AppContainer |
| `MainActivity.java` | `@AndroidEntryPoint`，`@Inject` 字段 + 工厂注入 |
| 约 6 个 A 类 | 构造器 `@Inject` |
| 约 8 个 B 类 | `@AssistedInject` + `@AssistedFactory` |
| `app/build.gradle.kts`、`libs.versions.toml` | Hilt 依赖 |

### 4.6 验收标准
- 所有功能不变
- `AppContainer` 删除
- A 类通过 `@Inject` 自动装配，B 类通过工厂注入
- Hilt 编译期图校验通过

---

## 5. 阶段 2：按层分包

**目标**：在 `:app` 模块内将 56 个平铺文件按职责分到子包，只移动文件 + 更新 import，不改逻辑。

### 5.1 分包方案

```
com.webterm.mobile/
├── di/                              ← Hilt 模块（阶段 1 产物）
├── data/
│   ├── api/      WebTermApi, WebTermUrls
│   ├── config/   ServerConfig, ServerConfigStore, ServerConfigManager
│   ├── cache/    TerminalCacheCoordinator, TerminalDiskCache, TerminalCacheScope,
│   │             CachedTerminal, CachedSessionMapper
│   └── repository/  SessionRepository
├── domain/
│   ├── session/  MuxSession, MuxTransport, RelayMuxSessionManager,
│   │             RelayMuxSessionRegistry, SessionIdentity, WebTermProtocol
│   ├── relay/    RelayCoordinator（暂留，阶段 4 拆分）
│   ├── server/   HomeServerCoordinator, ServerGroupController,
│   │             ServerSessionMonitor, HomeRefreshScheduler
│   ├── terminal/ TerminalConnection, TerminalLifecycleController,
│   │             TerminalRuntimeState, TerminalLaunchState,
│   │             TerminalClipboardController, TerminalTitleSynchronizer
│   └── command/  SessionCommandController
├── transport/
│   ├── WebSocketMuxTransport, WebRtcDataChannelTransport
│   ├── P2PConnectionManager, P2PDataChannelEndpoint
│   └── (WebTermProtocol 已归入 domain/session，见说明)
├── ui/
│   ├── MainActivity
│   ├── home/     HomeScreenBuilder, SessionRecyclerAdapter, SessionRowHelper,
│   │             SessionRowActions, SessionListItemViews, StatusIndicatorView
│   ├── terminal/ TerminalScreenBuilder, TerminalConnectionStatusView,
│   │             WebTermTerminalViewClient, WebTermTerminalSessionClient,
│   │             TerminalWindowInsetsController
│   ├── relay/    RelayLoginScreenBuilder, RelayDevicesScreenBuilder
│   ├── dialog/   ServerConfigDialogHelper, RenameSessionDialogHelper, SettingsDialogHelper
│   └── common/   DesignTokens, UIUtils, PageTransitionAnimator
├── recovery/    NetworkRecoveryController
├── WebTermApplication, CrashReporter
```

### 5.2 关键说明

- **WebTermProtocol 归属**：明确放入 `domain/session/`。它解析多路复用二进制帧，被 `MuxSession`（session）和 `TerminalConnection`（terminal）共用，属于会话协议层。不在 `transport/`。
- **可见性**：同包访问暂保持，跨包引用的类加 `public` 修饰符。
- **依赖方向**：`data/` 不依赖 `domain/`、`ui/`；`domain/` 不依赖 `ui/`；`domain/` 可依赖 `data/`。

### 5.3 验收标准
- 编译通过，功能不变
- 包结构清晰：`data/` `domain/` `ui/` `transport/` `di/` `recovery/`

---

## 6. 阶段 3：传输接口抽象（前置）

**目标**：在拆 `:core:session` 之前，先让 `MuxSession`/`RelayMuxSessionManager` 只依赖 `MuxTransport` 接口和 `TransportFactory` 接口，不再引用具体传输实现。**此阶段不新建 Gradle 模块**，仍在 `:app` 内完成接口抽取。

### 6.1 为何前置

`MuxSession` 和 `RelayMuxSessionManager` 当前直接引用 `WebSocketMuxTransport`、`WebRtcDataChannelTransport`。若不先抽象，`:core:session` 模块将被迫依赖具体传输实现（而这些实现在阶段 5 才独立成模块），导致模块依赖断裂。

### 6.2 引入 TransportFactory 接口

`domain/session/TransportFactory.java`（新建）：
```java
interface TransportFactory {
    /** 创建 WebSocket 传输，永不为 null */
    MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId);
    /** 创建 WebRTC DataChannel 传输，P2P 不可用时返回 null */
    MuxTransport createDataChannel(String deviceId);
}
```

### 6.3 重构 MuxSession / RelayMuxSessionManager

- 构造器不再接收具体传输类，改为接收 `MuxTransport`（已存在的接口）和 `TransportFactory`
- 内部需要新建传输时，调用 `factory.createXxx(...)`，不直接 `new WebSocketMuxTransport(...)`
- 删除对 `WebSocketMuxTransport`、`WebRtcDataChannelTransport` 的 import

### 6.4 实现 TransportFactory

`di/DefaultTransportFactory.java`（新建，临时放 `:app`，阶段 5 后仍在 `:app`）：
```java
@Singleton
final class DefaultTransportFactory implements TransportFactory {
    private final OkHttpClient http;
    private final P2PConnectionManager p2p;   // 构造器注入，永不为 null

    @Inject
    DefaultTransportFactory(OkHttpClient http, P2PConnectionManager p2p) {
        this.http = http;
        this.p2p = p2p;
    }

    @Override
    public MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId) {
        return new WebSocketMuxTransport(http, baseUrl, cookie, sessionId);
    }

    @Override
    public MuxTransport createDataChannel(String deviceId) {
        return p2p.getDataChannelTransport(deviceId);  // P2P 不可用时返回 null
    }
}
```

> 无 setter、无 null 检查。`P2PConnectionManager` 作为 A 类 @Singleton 由 Hilt 构造器注入。

### 6.5 消除 P2P 循环依赖

原循环：`RelayMuxSessionRegistry → TransportFactory → P2PConnectionManager → RelayMuxSessionRegistry`。

用 `Provider<RelayMuxSessionRegistry>` 打破构造期循环——`P2PConnectionManager` 在**构造期不持有 registry 实例**，仅持有 `Provider`；触发重连时才调用 `provider.get()`：

```java
@Singleton
final class P2PConnectionManager {
    private final OkHttpClient http;
    private final Provider<RelayMuxSessionRegistry> registryProvider;  // 延迟解析
    private volatile Listener listener;  // 运行时 setter 设置，volatile 保证可见性

    @Inject
    P2PConnectionManager(OkHttpClient http, Provider<RelayMuxSessionRegistry> registryProvider) {
        this.http = http;
        this.registryProvider = registryProvider;
    }

    void setListener(Listener listener) { this.listener = listener; }

    void onP2PDisconnected(String deviceId) {
        registryProvider.get().reconnectDevice(deviceId, "p2p disconnected");
    }
}
```

依赖图（Hilt 自动解析）：
```
RelayMuxSessionRegistry ──构造──→ TransportFactory(DefaultTransportFactory)
        ▲                              │
        │Provider<...>（延迟）         │
        │                              ▼
        └────────────────── P2PConnectionManager
```

- 三个类均为 `@Singleton`，构造器互相注入，Hilt 编译期可解析（Provider 打破环）
- `RelayMuxSessionRegistry` 原有的 `setTransportProvider(...)` 方法**删除**，factory 改为构造器注入
- 唯一手动步骤：`P2PConnectionManager.setListener(...)` 在 TerminalViewModel/Fragment 激活时设置回调
- 原 `setTransportProvider` 的 null 检查 lambda 彻底删除

### 6.6 验收标准
- `MuxSession`、`RelayMuxSessionManager` 无任何对 `WebSocketMuxTransport`/`WebRtcDataChannelTransport` 的引用
- 原 `setTransportProvider` 的 null 检查 lambda 删除
- 功能不变

---

## 7. 阶段 4：拆分 core 模块

**目标**：将 `data/` 和 `domain/` 底层基础设施抽出为独立 Gradle library 模块。同时创建 `:transport:api` 接口模块，避免接口定义在 `:core:session` 中暂存再迁移。

### 7.0a 创建 `:transport:api`（接口模块，提前于 transport 实现）

```
:transport:api/src/main/java/com/webterm/transport/api/
├── MuxTransport.java          ← 接口，从 domain/session/ 移入
└── TransportFactory.java      ← 接口，从 domain/session/ 移入
```
依赖：无（纯 Java 接口）

> `MuxTransport` 和 `TransportFactory` 从第一天就在正确位置，`:core:session` 直接 `implementation(:transport:api)`，避免阶段 5 二次迁移 import。

### 7.0b 前置：拆分 RelayCoordinator（必须先做）

`RelayCoordinator`（619 行）当前混合纯逻辑与 UI 状态，无法直接进 `:core:relay`。先拆为两部分：

**RelayService**（纯逻辑，将进 `:core:relay`）：
- HTTP 轮询、cookie 自动刷新、静默登录
- 登录/注册/OTP 验证
- 设备 CRUD、授信设备 CRUD
- 不持有 View，不实现 UI Host 接口
- 通过 LiveData + 公开方法暴露状态

**RelayUiState / RelayViewModel 雏形**（暂留 `:app` 的 `ui/relay/`，阶段 6 进 `:feature:relay`）：
- 8 种 `RelayState` 管理
- 实现 `RelayLoginScreenBuilder.Host`、`RelayDevicesScreenBuilder.Host`
- 持有 subtitle、status dot 等 View 引用
- 通过注入的 `RelayService` 获取数据

#### RelayService 公开 API 契约

```java
public class RelayService {
    // ── 状态（LiveData，observer 在 RelayUiState 中） ──
    public LiveData<RelayState> relayState();
    // IDLE → LOADING → ONLINE（有在线设备）/ OFFLINE（无设备）/ ERROR / AUTH_EXPIRED / AUTH_REQUIRED

    public LiveData<List<RelayDevice>> devices();        // 中继设备列表
    public LiveData<List<TrustedDevice>> trustedDevices(); // 授信设备列表
    public LiveData<String> statusText();                 // subtitle 文本
    public LiveData<Integer> onlineCount();               // 在线设备数

    // ── 生命周期 ──
    public void start(ServerConfig masterConfig);  // 开始轮询
    public void stop();                             // 停止轮询
    public void refresh();                          // 手动刷新

    // ── 认证 ──
    public void login(String email, String password);
    public void register(String email, String username, String password);
    public void verifyOtp(String code, String targetDeviceId);

    // ── 设备管理 ──
    public void deleteDevice(String deviceId);
    public void deleteTrustedDevice(String trustedDeviceId);

    // ── 回调（一次性事件，不走 LiveData） ──
    public interface AuthCallback {
        void onLoginSuccess(String baseUrl, String cookie);
        void onOtpRequired(String targetDeviceId, String cookie);
        void onError(String message);
    }
    public void setAuthCallback(AuthCallback callback);
}
```

#### RelayUiState 与 RelayService 的交互

```
RelayUiState（:app，实现 Host 接口）
    │
    │ observe(relayService.relayState())
    │ observe(relayService.devices())
    │ observe(relayService.onlineCount())
    │
    ▼
RelayService（:core:relay，纯逻辑，无 View）
    │
    │ relayService.login(email, password)
    │ relayService.deleteDevice(deviceId)
    │
    ▼
WebTermApi（HTTP）、OkHttpClient（轮询 Timer）
```

**关键**：`RelayUiState` 持有 `RelayService` 引用（注入），观察 LiveData 驱动 UI。`RelayService` 完全不感知 UI 层。

### 7.1 创建 `:core:api`

```
:core:api/src/main/java/com/webterm/core/api/
├── WebTermApi.java       ← 原样搬入（719 行，接口拆分留待后续可选优化）
└── WebTermUrls.java
```
依赖：`okhttp3`、`org.json`

### 7.2 创建 `:core:config`

```
:core:config/src/main/java/com/webterm/core/config/
├── ServerConfig.java
├── ServerConfigStore.java
└── ServerConfigManager.java
```
依赖：`androidx.annotation`、SharedPreferences（Android framework）

### 7.3 创建 `:core:cache`

```
:core:cache/src/main/java/com/webterm/core/cache/
├── TerminalCacheCoordinator.java
├── TerminalDiskCache.java
├── TerminalCacheScope.java
├── CachedTerminal.java
└── CachedSessionMapper.java
```
依赖：`:terminal-emulator`（需要 `TerminalSession`）

> 说明：cache 耦合 termux 终端是已知权衡，因缓存的就是终端会话快照。

### 7.4 创建 `:core:session`

```
:core:session/src/main/java/com/webterm/core/session/
├── MuxSession.java
├── RelayMuxSessionManager.java
├── RelayMuxSessionRegistry.java
├── SessionIdentity.java
└── WebTermProtocol.java
```
依赖：`:core:api`、`:transport:api`、`okhttp3`、`okio`。
> `MuxTransport` 和 `TransportFactory` 接口已在 `:transport:api`（小节 7.0a），不在此模块。

### 7.5 创建 `:core:relay`

```
:core:relay/src/main/java/com/webterm/core/relay/
└── RelayService.java     ← 阶段 7.0 拆分出的纯逻辑
```
依赖：`:core:api`、`:core:config`、`okhttp3`、`androidx.lifecycle:lifecycle-livedata:2.7.0`（RelayService 暴露 LiveData）、`Handler`（轮询 Timer，由 `:app` 注入）。**无 Android View 依赖**。

### 7.6 跨模块可见性

拆模块时，所有被跨模块 `@Inject` 注入的类必须：
- 类声明改 `public`
- 构造器改 `public` 并加 `@Inject`
- Hilt Module 跨模块可见（`@Module @InstallIn(SingletonComponent.class)`）

### 7.7 模块依赖图（阶段 4 结束后）

```
:core:config ──→ (无)
:core:api ─────→ okhttp3
:core:cache ───→ :terminal-emulator
:transport:api → (纯接口，无依赖)
:core:session ─→ :core:api, :transport:api
:core:relay ───→ :core:api, :core:config
:app ──────────→ 上述全部 + transport 实现类（仍在 :app）
```

### 7.8 验收标准
- 5 个新模块各自编译通过
- `:app` 编译通过，功能不变
- 模块间依赖无环
- `RelayService` 无 `android.view.*` import

---

## 8. 阶段 5：拆分 transport 实现模块

**目标**：将 WebSocket 和 WebRTC 实现各自成模块。`:transport:api` 接口模块已在阶段 4 创建。

### 8.1 创建 `:transport:websocket`

```
:transport:websocket/src/main/java/com/webterm/transport/websocket/
└── WebSocketMuxTransport.java
```
依赖：`:transport:api`、`okhttp3`

### 8.2 创建 `:transport:webrtc`

```
:transport:webrtc/src/main/java/com/webterm/transport/webrtc/
├── WebRtcDataChannelTransport.java
├── P2PConnectionManager.java
└── P2PDataChannelEndpoint.java
```
依赖：`:transport:api`、`:core:api`、`org.webrtc`

> `:transport:webrtc → :core:api` 是因为 P2P 信令（offer/answer/ICE candidate）需通过 HTTP API 交换。此依赖方向不是分层污染——P2P 信令的 HTTP 调用是 API 层职责，transport 模块仅依赖 API 接口而非实现。

### 8.3 DefaultTransportFactory 归属

`DefaultTransportFactory` 实现 `TransportFactory`（来自 `:transport:api`），但依赖 `:transport:websocket` + `:transport:webrtc` 两个实现模块。**只有 `:app` 同时依赖这两个实现模块**，因此 `DefaultTransportFactory` 放 `:app` 的 `di/` 包。

```
:app/di/DefaultTransportFactory.java   ← 依赖 :transport:websocket + :transport:webrtc
```

### 8.4 模块依赖图（阶段 5 结束后）

```
:transport:api ──────→ (纯接口)
:transport:websocket → :transport:api, okhttp3
:transport:webrtc ───→ :transport:api, :core:api, org.webrtc
:core:session ───────→ :core:api, :transport:api
:app ────────────────→ :transport:websocket, :transport:webrtc, :core:*, :feature:* (阶段6后)
```

### 8.5 验收标准
- `MuxSession` 只持有 `MuxTransport` 接口引用
- `DefaultTransportFactory` 在 `:app`，无 null 检查
- 三个 transport 模块独立编译

---

## 9. 阶段 6：拆分 feature 模块

**最高风险阶段**。当前项目零 Fragment，单 Activity 手动 `setContentRoot` + `PageTransitionAnimator` 切屏。本阶段引入 Fragment + ViewModel + Navigation Component，分两步走。

### 9.1 阶段 6a：在 `:app` 内 Fragment 化（先验证架构，不拆模块）

**目标**：先把 MainActivity 的 6 种屏幕模式改成 Fragment + ViewModel，仍在同一个 `:app` 模块内，降低风险。

新增依赖：
```kotlin
implementation("androidx.navigation:navigation-fragment:2.7.7")
implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
implementation("androidx.fragment:fragment:1.6.2")
```

新建：
- `ui/home/HomeFragment` + `HomeViewModel`
- `ui/terminal/TerminalFragment` + `TerminalViewModel`
- `ui/relay/RelayFragment` + `RelayViewModel`（接手小节 7.0 拆出的 `RelayUiState`）
- `ui/settings/SettingsFragment` + `SettingsViewModel`
- `res/navigation/nav_graph.xml`

#### 跨 Fragment 传参方案

当前 `showTerminal()` 传 8+ 参数（baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId, relayDeviceId, cwd）。**不通过 Bundle 传全部**，改为：

- Navigation 只传 `sessionId` + `baseUrl`（简单字符串，走 Bundle）
- `TerminalViewModel` 在 `onCreate` 时用 `sessionId` 从共享的 `SessionRepository` / `TerminalCacheCoordinator` 查询完整元数据
- 复杂数据对象不跨 Fragment 传递，避免 Parcelable 样板代码

#### ViewModel 与 View 操作分离

`TerminalLifecycleController` 直接操作 View（attachSession、keyboard、insets）。ViewModel **不得持有 View 引用**：
- 状态数据（sessionId、columns/rows、连接状态、lastSeq）→ ViewModel
- View 操作（attachSession、showKeyboard、insets）→ Fragment，通过观察 ViewModel 状态驱动

#### Host 接口消除映射

当前 MainActivity 实现了 10 个 Host/Listener 接口。Fragment 化后，每个接口的方法必须显式迁移：

| 接口 | 原实现者 | 迁移到 | 迁移方式 |
|------|---------|--------|---------|
| `SessionRowActions` | MainActivity | `HomeViewModel` | 方法体直接移到 ViewModel，Fragment 通过 `observe()` 驱动 UI |
| `TerminalConnection.Listener` | MainActivity | `TerminalViewModel` | `onConnected/onDisconnected/onOutput` → 更新 ViewModel 的 LiveData |
| `WebTermTerminalViewClient.Host` | MainActivity | `TerminalFragment` | 直接实现（Fragment 可作为 Host） |
| `WebTermTerminalSessionClient.Host` | MainActivity | `TerminalFragment` | 同上 |
| `ServerConfigDialogHelper.Host` | MainActivity | `HomeViewModel` | `onServerAdded/onServerRemoved` → ViewModel 方法 |
| `SettingsDialogHelper.Host` | MainActivity | `SettingsViewModel` | `getSavedFontSize/getSavedFontType` → 读 SharedPreferences |
| `RelayCoordinator.Host` | MainActivity | `RelayViewModel` | 已在阶段 7.0 拆分为 `RelayUiState` |
| `TerminalLifecycleController.Host` | MainActivity | `TerminalFragment` | 直接实现（`getSavedFontSize`、`installTerminalInsets` 等是 Fragment 职责） |
| `NetworkRecoveryController.Host` | MainActivity | App 级单例 | `onNetworkAvailableForRecovery()` → 单例暴露 `LiveData<Void>` 网络恢复事件，各 ViewModel 自行 observe |
| `P2PConnectionManager.Listener` | MainActivity | `TerminalViewModel` | `onConnected/onDisconnected` → 更新 LiveData |

迁移原则：
- **数据型回调**（`onConnected`、`onOutput`、`onState`）→ ViewModel LiveData
- **UI 操作型回调**（`installTerminalInsets`、`setContentRoot`）→ Fragment 直接实现
- **全局事件**（`onNetworkAvailableForRecovery`）→ 共享单例 + LiveData，各 ViewModel 自行 observe

#### 页面转场

`PageTransitionAnimator` 基于 View 直接替换。Fragment 化后改用 Fragment 事务的 `setCustomAnimations`，需重写动画资源。本阶段先保留 `PageTransitionAnimator` 给非导航场景，导航转场用 Fragment 动画。

#### 阶段 6a 验收
- 4 个 Fragment + ViewModel 可工作，与原单 Activity 行为一致
- Navigation Component 导航正常，页面切换动画无闪烁
- 返回键行为一致：终端页弹出"确认退出"弹窗，其余页正常返回
- 横竖屏旋转时终端会话不丢失（ViewModel 跨 config change 存活）
- 后台恢复时连接状态正确（网络恢复后自动重连）
- MainActivity 仍存在但逻辑已迁移至各 Fragment

### 9.2 阶段 6b：拆为独立 Gradle 模块

**目标**：把 4 个 feature 各自拆成 Gradle library 模块，含资源迁移。

#### 模块结构

```
:feature:home/src/main/java/com/webterm/feature/home/
├── HomeFragment, HomeViewModel
├── HomeScreenBuilder, SessionRecyclerAdapter, SessionRowHelper
├── SessionRowActions, SessionListItemViews, StatusIndicatorView
└── ServerConfigDialogHelper, RenameSessionDialogHelper

:feature:terminal/...   TerminalFragment, TerminalViewModel, TerminalScreenBuilder,
                        TerminalConnectionStatusView, WebTermTerminalViewClient,
                        WebTermTerminalSessionClient, TerminalWindowInsetsController

:feature:relay/...      RelayFragment, RelayViewModel, RelayLoginScreenBuilder,
                        RelayDevicesScreenBuilder

:feature:settings/...   SettingsFragment, SettingsViewModel, SettingsDialogHelper
```

#### 资源迁移（关键）

每个 feature 模块带自己的 `src/main/res/`：
- `:feature:home` → `layout/home_*.xml`、`layout/session_row.xml`、相关 drawable
- `:feature:terminal` → `layout/terminal_*.xml`、快捷键 drawable
- `:feature:relay` → `layout/relay_*.xml`
- `:feature:settings` → `layout/settings*.xml`
- 公共资源（`colors.xml` 基础色、通用 `strings`、`DesignTokens` 相关）留在 `:app` 或抽 `:core:ui`（可选）

**资源冲突处理**：多模块资源合并时需注意：
1. **同名资源冲突**：AGP 默认不允许不同模块定义同名资源。迁移前全局检索 `res/` 中所有 layout/drawable/string ID，重名者按模块加前缀（如 `home_session_row`、`terminal_status_dot`）
2. **R 类引用**：在 feature 模块内部，`R.layout.xxx` 自动解析为该模块的 R 类。`:app` 中引用聚合 R 类（`com.webterm.mobile.R`）。迁移后需检查所有 `R.` 引用是否指向正确模块
3. **公共资源保留**：基础色值、通用字符串、`DesignTokens` 相关 drawable 留在 `:app` 的 `res/`，避免各模块重复定义

#### 依赖

```
:feature:home ──────→ :core:api, :core:config, :core:session
:feature:terminal ──→ :terminal-view, :core:session, :core:cache, :transport:api
:feature:relay ─────→ :core:relay, :core:config
:feature:settings ──→ :core:config
:app ───────────────→ 全部 feature 模块 + DefaultTransportFactory
```

#### 阶段 6b 验收
- 4 个 feature 模块独立编译
- 资源无合并冲突
- `R.layout.*` 引用正确指向各模块资源

---

## 10. 阶段 7：拆解 MainActivity

**目标**：MainActivity 从 798 行降至 ~100 行。

### 10.1 MainActivity 保留职责

```java
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);  // 仅 NavHostFragment
    }
    // 仅保留：返回键拦截、全局 AppBar、生命周期转发
}
```

### 10.2 职责迁移对照

| 原 MainActivity 职责 | 迁移到 |
|----------------------|--------|
| 会话列表 + 服务器管理 + 下拉刷新 | `HomeFragment` + `HomeViewModel` |
| 终端连接 + 渲染 + 快捷键 | `TerminalFragment` + `TerminalViewModel` |
| 中继登录/注册/设备管理 | `RelayFragment` + `RelayViewModel` |
| 设置 | `SettingsFragment` + `SettingsViewModel` |
| Swipe-to-delete | `HomeFragment` 内 `ItemTouchHelper` |
| 屏幕导航 | `nav_graph.xml` + `findNavController().navigate()` |
| 子系统创建 | Hilt 自动装配 |
| 后台 detach 定时器 | `TerminalViewModel` |
| 崩溃日志分享 | `CrashReporter` 独立类 |
| 网络恢复监听 | `NetworkRecoveryController`（放 `:app`，作为 app 级单例注入需要重连的 ViewModel） |

### 10.3 验收标准
- MainActivity ≤ 120 行
- 10 个 Host 接口全部消除
- 每个 Fragment 有自己的 ViewModel
- 屏幕切换通过 Navigation Component

---

## 11. 阶段 8：测试验证

**目标**：验证 DI 图正确、core 模块可独立测试。

### 11.1 每个 core 模块至少 1 个单元测试

测试依赖（各模块 `build.gradle.kts` 中 `testImplementation`）：
```kotlin
testImplementation(libs.junit)
testImplementation("org.json:json:20240303")
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")  // :core:api 测试用
```

- `:core:api`：`WebTermApi` 的 cookie 合并、错误解析（mockwebserver 模拟 HTTP 响应）
- `:core:config`：`ServerConfigStore` 序列化/反序列化
- `:core:cache`：`TerminalDiskCache` 读写、过期清理
- `:core:session`：`MuxSession` 重连退避、`SessionIdentity` 规范化
- `:core:relay`：`RelayService` 401 重试链

### 11.2 Hilt 图验证

Hilt 测试依赖（`:app` 的 `androidTestImplementation`）：
```kotlin
androidTestImplementation("com.google.dagger:hilt-android-testing:2.51")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
```

- `@HiltAndroidTest` 集成测试，确认所有 `@Inject` 和 `@AssistedInject` 可装配
- 临时替换 `DefaultTransportFactory` 的 transport 实现为 mock，验证 `MuxSession` 只依赖接口

### 11.3 验收标准
- 每个 core 模块测试通过
- Hilt 图可装配

---

## 12. 完整模块依赖总图

```
                        :app (壳 + DefaultTransportFactory)
                      /   |   |   \
                     /    |   |    \
        :feature:home  :feature  :feature  :feature
                       :terminal :relay   :settings
             |           /  \       |        |
             |          /    \      |        |
        ┌────┴────┬────┴──┐ ┌─┴────────┐   |
        |         |       | |          |   |
   :core:api  :core:session :core:relay :core:config
                  |                      
           :transport:api              
              /         \              
    :transport:websocket  :transport:webrtc
                              |
                          :core:api

   :core:cache ────→ :terminal-emulator ←─── :terminal-view
```

## 13. 风险与回滚

- **每阶段独立提交**，便于回滚。若某阶段引入问题，可回退到上一阶段提交点。
- **阶段 6a 是最大风险点**：Fragment 化若问题过大，可停留在阶段 5（已完成模块化但仍是单 Activity），后续再重启 Fragment 化。
- **不强制按顺序做完所有阶段**：阶段 1-5 完成后已获得分层和可测试性收益，可随时停。

## 14. 不在本次范围 / 可选的后续优化

- `WebTermApi` 内部按 AuthApi/SessionApi/DeviceApi/P2PApi 拆分接口——阶段 4 后 `:core:api` 已独立，可随时做接口拆分而不影响其他模块
- Kotlin 迁移
- Compose 迁移
- `:terminal-emulator`/`:terminal-view` 独立为 Maven 坐标引用
