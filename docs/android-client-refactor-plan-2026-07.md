# Android 客户端架构重构计划（2026-07）

## 背景

本文档依据 2026-07-13 的架构审查结论制定。安卓端的分层骨架方向正确：transport-api / transport-websocket 接口-实现分离、terminal-model / terminal-protocol / terminal-renderer 三段式终端链路、core-session 的 mux 抽象都体现了明确的架构意图。问题集中在分层纪律失守、重构残留和重复实现：

- `AppFlowCoordinator`（`app/.../AppFlowCoordinator.java`，879 行）是 god class：导航状态机 + 鉴权刷新 + 会话命令 + 下载 + 崩溃日志 + 字体管理，且长期持有 `MainActivity` / `NavController` / `HomeFragment` 引用并反向调用 Fragment。
- 53,205 行 protobuf 生成代码签入源码树（`terminal-protocol/.../generated/TerminalScreenProto.java`，该模块手写代码仅 631 行）。
- core-relay 持有 UI 状态（`MutableLiveData`、中文文案、颜色常量），`Host.activity()` 把 Activity 泄漏进 core 层；ui-common 反向依赖 core-api/core-config 做网络编排。
- 鉴权恢复逻辑（401 → refresh → 密码降级）复制 4 份，细节各不相同；会话 ID 前缀转换逻辑 3 份。
- 测试归属倒置：app/src/test 下 16 个测试文件中 11 个测的是其他模块的类；core-api / core-cache / core-config / core-relay / ui-common 本地零测试；androidTest 里有永远编译不过的模板死测试。
- 死代码：下载链路断链（`FileDownloadHelper.startDownload` 空实现）、`TerminalProjection` 生产零引用、`ServerSessionMonitor` 半迁移残留。
- `core-config/ServerConfigStore.java:16` 把私有 IP 和 `admin` 签入作默认值，密码明文存 SharedPreferences。

## 目标

```text
1. feature 模块不再通过 Host 接口反向依赖 app；AppFlowCoordinator 拆分或退化为纯装配
2. 鉴权恢复与会话 ID 转换逻辑全局各只有一份实现
3. core 层不持有 UI 状态，ui-common 不依赖数据层
4. 生成代码不入库，协议常量来自单一源
5. 每个 core / feature 模块的测试位于本模块且 CI 可跑
6. 死代码与模板残留清零
```

## 非目标

```text
不迁移到 Compose（删除模板残留即可）
不全量引入协程重写（只统一 executor 模型；coroutines 依赖是否保留在 Phase 4 评估）
不改 UI 视觉与交互设计
不重复 file-send-device-service-refactor-plan 已规划的 DeviceConnection / WebTermDeviceService 内容，
本计划 Phase 3 与该计划同批次落地
不改模块划分（terminal-interaction 并入 terminal-renderer 属可选优化，放最后）
```

## Phase 0：卫生清理（1 人日）

### 0.1 死代码删除

- 删 `app/src/androidTest/java/com/example/webtermmobile/ui/main/MainScreenTest.kt`（模板残留：包名 `com.example.webtermmobile`、引用不存在的 `MainScreen`、依赖未应用的 Compose 插件，永远编译不过）。
- 删 `feature/terminal/domain/TerminalProjection.java` 及其测试（生产代码零引用，与 commit `66759b8` 只删了一半相符）。
- 删 `feature/home/domain/ServerSessionMonitor.java:31` 的无调用方实例构造 API，保留实际使用的静态 `dispatchMessage`。
- 下载死链路（`FileDownloadHelper.startDownload` 空实现、`AppFlowCoordinator.startFileDownload` 无调用方、`WebTermApi.downloadFile` 无调用方）：若 file-send 计划即将落地则整体移除，由 WebTermDeviceService 链路替代；否则先标记 `@Deprecated` 并在 Phase 3 删除。
- 修 `AppFlowCoordinator.java:19,21` 的 `org.json.JSONObject` 重复 import。

### 0.2 生成物移出源码树

- 引入 protobuf Gradle 插件，生成目录改为 `build/generated/`，`git rm --cached terminal-protocol/src/main/java/com/webterm/terminal/protocol/generated/TerminalScreenProto.java` 并加入 `.gitignore`。
- `scripts/generate-proto.sh` 的 Java 侧输出同步废弃（Go 侧生成保留），`tools/protoc` 的版本约束改由插件声明。
- CI 加"proto 与生成物一致"校验。

### 0.3 依赖与配置卫生

- 删 `core-api/build.gradle.kts:16` 未使用的 transport-api 依赖；`app/build.gradle.kts:66` 的 okhttp 依赖改经 core 层传递。
- 硬编码依赖版本（okhttp 4.12.0 ×5 模块、annotation 1.9.0 ×9 模块等）迁入 `gradle/libs.versions.toml`；删除 catalog 中无模块使用的死条目（compose-bom / navigation3 / coroutines 待 Phase 4 评估后决定）。
- 删 terminal-model / protocol / renderer 中冗余的 `sourceSets` 默认配置。
- 移除 `core-config/ServerConfigStore.java:16-17` 的 `DEFAULT_URL`（私有 IP）与 `DEFAULT_USER`：未配置时走首次启动引导输入，不再内置默认值。

### 验收

- `./gradlew assembleAndroidTest` 编译通过（此前因模板测试失败）。
- `terminal-protocol` 模块源码树只剩手写代码（Builder / Mapper / Validator）。
- `grep -rn "100.121.115.14" android-client` 无命中；无配置时启动进入引导页。

## Phase 1：协议常量单一源（1-2 人日，与 Go 计划 Phase 1 联动）

- 消费 Go 计划 Phase 1.3 产出的常量源：`WebTermProtocol.java:11-22` 的手写消息类型、`RelayMuxSessionManager.java:17-18` 的子协议字符串、`MuxSession.java:83,104,204-216` 的 mux 控制消息类型，改为构建期生成（从同一 JSON/proto 源生成 Java 常量类）或至少集中到一个 `ProtocolConstants` 类并标注"以 go-core/internal/protocol/constants.go 为准"。
- 接入 Go 计划 Phase 1.4 的 `tests/fixtures/` 帧样例，在 core-session 加 JUnit 编解码互验测试。
- 会话 ID 前缀转换逻辑三份（`core-api/SessionIds.java:7-19`、`core-session/RelayMuxSessionManager.java:293-306`、`core-api/WebTermApi.java:623-634`）收敛到 `SessionIds`，删另外两份。

### 验收

- 全项目消息类型常量 / 子协议字符串 / 控制消息类型各只有一处定义。
- fixture 测试在 `./gradlew :core-session:test` 中通过；任一端改常量三端测试同时红。

## Phase 2：分层修复（2-3 人日）

### 2.1 core-relay 去 UI 化

- `RelayService` 移除 `MutableLiveData<String> subtitleText` 等 UI 状态与硬编码文案/颜色（`RelayService.java:55-63, 423-449`），对外只暴露 `RelayState` 枚举与状态流；文案/颜色映射移到 feature:relay 的 ViewModel。
- 删除 `STATUS_*` 常量与 "keep in sync with StatusIndicatorView.Status" 的人工同步约定（`RelayService.java:28-31`），由映射层一处转换。
- 删除 `RelayService.Host.activity()`（`RelayService.java:455`，Activity 类型泄漏进 core 层且从未被调用；实现方在 `AppFlowCoordinator.java:628`）。

### 2.2 ui-common 回归纯 UI

- `SessionCommandController`（`ui-common/command/`，在做 401→refresh→login 的网络编排）移出 ui-common，目标位置 core-session 或新建 core-command 模块；ui-common 去掉 `build.gradle.kts:22-23` 对 core-api / core-config 的依赖。
- ui-common 只保留纯 UI 组件（StatusIndicatorView 等）。

### 2.3 core-api 名实相符

- `WebTermApi.java`（704 行，完整 OkHttp 客户端实现）拆分为：接口留在 core-api，实现移到新模块 core-network（或直接改名现模块为 core-network 并在 core-api 放接口，二选一取 diff 更小者）。
- 顺带合并重复重载：`createSession`×2（289/326 行）、`deleteSession`×2（378/390 行）；手写 cookie 合并逻辑（586-621 行）随 Phase 3 的 AuthCoordinator 收敛。

### 2.4 解除 feature 间耦合

- `RelayUiState` 从 feature:relay 移到 ui-common（或让 core-relay 暴露状态接口），消除 `feature:home → feature:relay` 的编译依赖（`feature/home/build.gradle.kts:25`）。
- `HomeViewModel.java:82` 不再把 `RelayService` 类型透传给 Fragment；`HomeFragment.java:110` 的 `new RelayUiState(mViewModel.getRelayService(), null)` 改为消费状态流。

### 验收

- 依赖图：core-relay 不再 import `androidx.lifecycle.LiveData` 与 UI 类；ui-common 不依赖任何 core-* 数据模块；feature:home 不依赖 feature:relay。
- 建议在 `scripts/` 加一个依赖方向校验（Gradle task 或简单的 import grep 检查），防回归。

## Phase 3：AppFlowCoordinator 拆分与鉴权收敛（3-4 人日）

与 `file-send-device-service-refactor-plan.md` 的 WebTermDeviceService 同批次落地：连接所有权归 DeviceService 后，coordinator 持有的连接/会话职责自然上移，本 Phase 处理剩余部分。

### 3.1 拆分三步走（每步独立可编译、可回滚）

1. **抽接口**：把 `MainActivity`（实现 5 个 Host 接口纯转发，`MainActivity.java:29,64-98`）与 coordinator 的交互改为事件回调 / 状态流，feature 模块依赖接口而非 app 实现。
2. **迁逻辑**：
   - 导航状态机（`ScreenMode`，92-98 行）与 Fragment 反向调用（374-380、656-659 行）→ `Navigator`（基于 Navigation 组件）。
   - 鉴权刷新（518-545 行）→ `AuthCoordinator`（见 3.2）。
   - 会话命令 → `SessionCommands`（与 2.2 移出的 SessionCommandController 合并）。
   - 下载目录选择（722-745 行）、崩溃日志分享（751-766 行）、字体管理 → 各自的独立组件或对应 feature。
3. **删转发**：移除 coordinator 对 `MainActivity` / `NavController` / `HomeFragment` 的长期引用（87-90 行，`@ActivityScoped` 持有 Activity 有泄漏风险）；coordinator 删除或退化为 < 200 行的纯装配代码。

### 3.2 鉴权收敛

- 四份 401→refresh→密码登录降级实现（`core-relay/RelayService.java:202-279`、`ui-common/command/SessionCommandController.java:40-64,136-158`、`feature/home/repository/SessionRepository.java:540-590`、`app/ui/AppFlowCoordinator.java:518-545`）统一为 `AuthCoordinator` 单一实现（放在 core 层，重试上限与 cookie 比对细节以 `SessionRepository` 版本为基准，其余三处对齐后删除）。
- 四处调用点改为注入 `AuthCoordinator`。

### 验收

- `grep -rn "refreshAndRetry\|relogin" android-client` 只剩一处实现。
- feature:terminal 与 feature:home 可在不依赖 app 模块的情况下编译各自的测试。
- 泄漏检查：无 `@ActivityScoped` 对象持有 Activity / Fragment 字段。

## Phase 4：线程模型与测试基础（2 人日）

### 4.1 统一 executor

- `app/di/AppModule.java:56-60` 的无 qualifier `Executor` 绑定改为 `@IoExecutor` / `@MainExecutor` 命名绑定。
- `core-cache/TerminalDiskCache.java:29` 的自建单线程池、`feature/terminal/domain/TerminalSessionRuntime.java:133-141` 的每会话线程，改为注入命名 executor。
- `TerminalSessionRuntime` 的 `UncaughtExceptionHandler`（136-139 行，TODO 静默吞异常）至少接 `Log.e`；`new Handler(Looper.getMainLooper())` 临时实例（130、154 行）改为注入 mainHandler。
- 评估是否引入协程：catalog 已声明 coroutines 1.10.2 但全项目零使用。结论二选一——新代码开始用协程（TerminalSessionRuntime 的事件环是首选切入点），或删除 catalog 死条目。不要维持现状。
- `MuxSession.java:22,235-243` 与 `TerminalSessionRuntime.java:34-40` 的硬编码连接/退避/resync 参数集中到配置类。

### 4.2 测试归位与 CI

- app/src/test 下 11 个测试其他模块类的文件迁回被测模块的 `src/test`（含用 `com.webterm.mobile` 包名测 core-session 类的 `RelayMuxSessionManagerTest.java`）；core-session 分裂在两地的 5 个测试合并回 core-session。
- 迁完后每个 core-* / feature 模块本地都有测试；feature:home（3385 行，当前本地 0 测试）至少补 SessionRepository 与 ViewModel 的核心路径测试。
- 密码存储从明文 SharedPreferences（`ServerConfigStore.java:47-56`）换为 EncryptedSharedPreferences。
- CI 接入 `./gradlew test` 与 `assembleAndroidTest`；`scripts/run_all_tests.sh` 改为真正聚合 Go + Android + 前端测试（当前只跑 Go 的一个包却宣称"100% 通过"）。

### 验收

- `./gradlew test` 全绿且覆盖全部模块；CI 包含 Android 任务。
- 全项目 executor 来自 DI；无静默吞异常的 handler。

## 可选优化（不阻塞主线）

- `terminal-interaction` 模块（1 个 78 行类，唯一消费者是 terminal-renderer）并入 terminal-renderer。
- `RemoteTerminalView.java`（1102 行）的 `RemoteTerminalInputConnection` 内部类（约 157 行）拆成独立文件；输入链路让 InputConnection 直接回调语义事件，不再合成 `KeyEvent(KEYCODE_DEL)` 上抛后重新解码（971 行）。
- applicationId `com.webterm.mobile.c2`、包名 `com.webterm.mobile`、仓库目录 `webterm-c1` 的命名对齐。

## 风险与回滚

- Phase 0.2 改生成目录需要 IDE 与 CI 同步调整；先确保本地 `./gradlew build` 与 CI 都生成成功再 `git rm` 旧文件。
- Phase 2 改的是模块边界，每动一个模块边界跑一次全量 `./gradlew build`；2.3 的拆分若 diff 过大，可先只移实现、接口暂留原位。
- Phase 3 是全线风险最高点：coordinator 被 9 个注入依赖和所有 feature 引用。严格按"抽接口 → 迁逻辑 → 删转发"三步小步走，每步独立 PR；WebTermDeviceService 未就绪时不启动 3.1 第 3 步。
- Phase 1 依赖 Go 计划 Phase 1 的常量源与 fixture 产出；若 Go 侧延期，Java 侧先做"集中到 ProtocolConstants"的降级方案，生成化后补。

## 与 Go 计划及其他文档的关系

- 协议常量与 fixture：Go 计划 Phase 1.3/1.4 为源头，本计划 Phase 1 消费。
- `file-send-device-service-refactor-plan.md`：本计划 Phase 0.1 的下载死链路由其替代，Phase 3 与其 WebTermDeviceService 同批次落地。
- `android-go-authoritative-terminal-rendering-plan.md`：终端链路三段式是其成果，本计划不动该链路结构。

## 工作量汇总

```text
Phase 0  卫生清理               1   人日
Phase 1  协议常量单一源         1-2 人日
Phase 2  分层修复               2-3 人日
Phase 3  coordinator 拆分       3-4 人日（与 file-send 计划并行）
Phase 4  线程与测试基础         2   人日
合计                            9-12 人日
```
