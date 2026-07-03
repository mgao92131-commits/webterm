# Phase 4 Report — Tasks 4.0b through 4.5

## Status: DONE

All 6 tasks completed successfully. BUILD SUCCESSFUL for each task.

## Commits Created

| Commit | Message |
|--------|---------|
| `31a120b` | refactor: 拆分 RelayCoordinator 为 RelayService + RelayUiState |
| `b02a200` | feat: 拆出 :core:api 模块 |
| `b02a200` | feat: 拆出 :core:config 模块 |
| `b02a200` | feat: 拆出 :core:cache 模块 |
| `b02a200` | feat: 拆出 :core:session 模块 |
| `b02a200` | feat: 拆出 :core:relay 模块 |

## Task 4.0b: Split RelayCoordinator → RelayService + RelayUiState

- **RelayService** (`core-relay/src/.../RelayService.java`): `@Singleton` + `@Inject` constructor, all business logic (HTTP polling, cookie refresh, login/register/verifyOtp, device management), exposes state via `LiveData` (subtitleText, subtitleColor, statusDotStatus), retains `Host` interface for MainActivity callbacks
- **RelayUiState** (`app/src/.../ui/relay/RelayUiState.java`): implements `RelayLoginScreenBuilder.Host` + `RelayDevicesScreenBuilder.Host`, holds `RelayService` reference, observes `LiveData` to update UI elements (subtitle TextView, status dot), bridges screen builder callbacks to `RelayService`
- **Deleted**: `RelayCoordinator.java`
- **MainActivity**: replaced `RelayCoordinator mRelayCoordinator` with `@Inject RelayService mRelayService` + `RelayUiState mRelayUiState`, now implements `RelayService.Host`

## Task 4.1: Create :core:api module

- Files moved: `WebTermApi.java`, `WebTermUrls.java`
- Package: `com.webterm.mobile.data.api` → `com.webterm.core.api`
- Deps: `hilt.android`, `hilt.compiler`, `project(":transport-api")`

## Task 4.2: Create :core:config module

- Files moved: `ServerConfig.java`, `ServerConfigStore.java`, `ServerConfigManager.java`
- Package: `com.webterm.mobile.data.config` → `com.webterm.core.config`
- Deps: `hilt.android`, `hilt.compiler`

## Task 4.3: Create :core:cache module

- Files moved: `TerminalCacheCoordinator.java`, `TerminalDiskCache.java`, `TerminalCacheScope.java`, `CachedTerminal.java`, `CachedSessionMapper.java`
- Package: `com.webterm.mobile.data.cache` → `com.webterm.core.cache`
- Deps: `hilt.android`, `hilt.compiler`, `project(":core-config")`

## Task 4.4: Create :core:session module

- Files moved: `MuxSession.java`, `RelayMuxSessionManager.java`, `RelayMuxSessionRegistry.java`, `SessionIdentity.java`, `WebTermProtocol.java`
- Package: `com.webterm.mobile.domain.session` → `com.webterm.core.session`
- Deps: `hilt.android`, `hilt.compiler`, `project(":transport-api")`

## Task 4.5: Create :core:relay module

- Files moved: `RelayService.java` (created in 4.0b)
- Package: `com.webterm.mobile.domain.relay` → `com.webterm.core.relay`
- Deps: `hilt.android`, `hilt.compiler`, `project(":core-api")`, `project(":core-config")`, `project(":core-session")`
- Updated imports in `RelayUiState.java` and `MainActivity.java`

## Build Result

All tasks: `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**

## Module Graph After Phase 4

```
:app
 ├── :terminal-emulator
 ├── :terminal-view
 ├── :transport-api
 ├── :core-api       (WebTermApi, WebTermUrls)
 ├── :core-config    (ServerConfig, ServerConfigStore, ServerConfigManager)
 ├── :core-cache     (TerminalCache*, CachedTerminal, CachedSessionMapper)
 ├── :core-session   (MuxSession, RelayMuxSessionManager, RelayMuxSessionRegistry, SessionIdentity, WebTermProtocol)
 └── :core-relay     (RelayService)
      ├── :core-api
      ├── :core-config
      └── :core-session
```
