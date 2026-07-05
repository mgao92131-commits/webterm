# Phase 4 Module Extraction Fix Report

## Status: DONE

## Build Result: BUILD SUCCESSFUL

## Summary

Fixed all package declarations, stale imports, and missing dependencies across the 6 new Gradle modules created in Phase 4. The build now succeeds with `./gradlew :app:assembleDebug`.

## Issues Fixed

### 1. Package Declarations (5 files)

| File | Old Package | New Package |
|------|-------------|-------------|
| `core-api/.../WebTermUrls.java` | `com.webterm.mobile.data.api` | `com.webterm.core.api` |
| `core-config/.../ServerConfig.java` | `com.webterm.mobile.data.config` | `com.webterm.core.config` |
| `core-config/.../ServerConfigManager.java` | `com.webterm.mobile.data.config` | `com.webterm.core.config` |
| `core-config/.../ServerConfigStore.java` | `com.webterm.mobile.data.config` | `com.webterm.core.config` |
| `core-relay/.../RelayService.java` | `com.webterm.mobile.domain.relay` | `com.webterm.core.relay` |

### 2. Stale Imports in Moved Modules (3 files)

| File | Old Import | New Import |
|------|------------|------------|
| `core-api/.../WebTermApi.java` | `com.webterm.mobile.data.config.ServerConfig` | `com.webterm.core.config.ServerConfig` |
| `core-cache/.../TerminalCacheCoordinator.java` | `com.webterm.mobile.domain.session.SessionIdentity` | `com.webterm.core.session.SessionIdentity` |
| `core-cache/.../TerminalDiskCache.java` | `com.webterm.mobile.domain.session.SessionIdentity` | `com.webterm.core.session.SessionIdentity` |

### 3. Stale Imports in :app Module (2 files)

| File | Fix |
|------|-----|
| `app/.../MainActivity.java` | `com.webterm.mobile.domain.relay.RelayService` → `com.webterm.core.relay.RelayService` |
| `app/.../RelayUiState.java` | 8 fully-qualified `com.webterm.mobile.data.api.WebTermApi.*` → `com.webterm.core.api.WebTermApi.*` |

### 4. Missing Dependencies

| Module | Added Dependencies |
|--------|--------------------|
| `core-api` | `project(":core-config")`, `okhttp:4.12.0` |
| `core-session` | `project(":core-api")`, `okhttp:4.12.0` |
| `core-cache` | `project(":core-api")`, `project(":core-session")`, `project(":terminal-emulator")` |
| `core-relay` | `okhttp:4.12.0` |

### 5. ServerConfig Import Verification

`WebTermApi.java` uses `ServerConfig` in method signatures (`fetchSessions`, `createSession`, `renameSession`, `deleteSession`). The import was stale (old package) but needed — fixed to `com.webterm.core.config.ServerConfig` and added `project(":core-config")` dependency. No circular dependency (core-config does not depend on core-api).

## Files Changed (15 total)

- `app/build.gradle.kts` — added 5 project dependencies
- `app/.../MainActivity.java` — fixed RelayService import
- `app/.../RelayUiState.java` — fixed 8 WebTermApi references
- `core-api/build.gradle.kts` — added core-config + okhttp
- `core-api/.../WebTermApi.java` — fixed ServerConfig import
- `core-api/.../WebTermUrls.java` — fixed package declaration
- `core-cache/build.gradle.kts` — added core-api, core-session, terminal-emulator
- `core-cache/.../TerminalCacheCoordinator.java` — fixed SessionIdentity import
- `core-cache/.../TerminalDiskCache.java` — fixed SessionIdentity import
- `core-config/.../ServerConfig.java` — fixed package declaration
- `core-config/.../ServerConfigManager.java` — fixed package declaration
- `core-config/.../ServerConfigStore.java` — fixed package declaration
- `core-relay/build.gradle.kts` — added okhttp
- `core-relay/.../RelayService.java` — fixed package declaration
- `core-session/build.gradle.kts` — added core-api + okhttp
