# Task 6a Report: Fragment-ization + Navigation

## Status: DONE

## Commit
`f77a287` - `refactor: MainActivity Fragment 化，引入 Navigation`

## Build Result
**BUILD SUCCESSFUL** — `./gradlew :app:assembleDebug` passes cleanly.

## Summary

Phase 6a 完成了 MainActivity 的 Fragment 化改造，引入 Jetpack Navigation 组件管理页面切换。

### Files Created (5 new)

| File | Purpose |
|------|---------|
| `app/src/main/res/layout/activity_main.xml` | FragmentContainerView + NavHostFragment 布局 |
| `app/src/main/res/navigation/main_nav_graph.xml` | 导航图：homeFragment / terminalFragment / relayFragment |
| `app/src/main/java/.../ui/home/HomeFragment.java` | 首页 Fragment，管理设备列表和设备会话两种状态 |
| `app/src/main/java/.../ui/terminal/TerminalFragment.java` | 终端 Fragment，提供 FrameLayout 容器 |
| `app/src/main/java/.../ui/relay/RelayFragment.java` | 中转 Fragment，管理中转登录和设备管理 |

### Files Modified (2 existing)

| File | Changes |
|------|---------|
| `app/build.gradle.kts` | 添加 `navigation-fragment:2.8.5` 和 `navigation-ui:2.8.5` 依赖 |
| `app/src/main/java/.../ui/MainActivity.java` | 从 ComponentActivity 改为 FragmentActivity；使用 NavController 导航；添加公共 getter 方法供 Fragment 调用 |

### Architecture Decisions

1. **FragmentActivity 替代 ComponentActivity**：Fragment 的 `requireActivity()` 返回 `FragmentActivity`，MainActivity 需要继承它才能被 Fragment 安全地强制转换。

2. **MainActivity 保持 Coordinator 角色**：所有 Hilt 注入的对象、TerminalLifecycleController、HomeServerCoordinator 等复杂生命周期对象仍然在 MainActivity 中创建和管理。Fragment 通过 `(MainActivity) requireActivity()` 获取这些共享状态。

3. **HomeFragment 内部状态切换**：首页（设备列表）和设备会话页在 HomeFragment 内部通过 `showHomeScreen()` / `showDeviceSessions(server)` 切换，不通过 NavController。这保留了原有的导航行为（设备会话不是独立的导航目的地）。

4. **TerminalFragment 容器模式**：终端视图由 TerminalLifecycleController 构建，通过 `setTerminalContent(View)` 方法注入到 TerminalFragment 的 FrameLayout 中。

5. **导航流程**：
   - 首页 → 设备会话：HomeFragment 内部切换
   - 首页 → 终端：`NavController.navigate(R.id.terminalFragment, args)`
   - 首页 → 中转：`NavController.navigate(R.id.relayFragment)`
   - 返回：`NavController.popBackStack()` + Fragment 状态恢复

### Constraints Preserved

- Pure Java 17, annotationProcessor (no kapt)
- compileSdk=36, minSdk=23
- All AlertDialog use `setCanceledOnTouchOutside(false)`
- No functional behavior changes
- All existing interfaces (Host, Listener, etc.) preserved
