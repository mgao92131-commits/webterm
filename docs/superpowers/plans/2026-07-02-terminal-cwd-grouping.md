# 终端分组目录修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android 终端列表按真实工作目录(cwd)正确分组并随 `cd` 实时刷新,而非全部塌缩到"未同步目录"或停留在旧分组。

**Architecture:** 分三层修复。(1) 服务器端:`go-headless-term` 库已内置 OSC 7 解析(写入 `workingDir`),但 `TerminalSession.Info()` 仍返回创建时的固定 cwd;让 `Info()` 在 OSC 7 上报后返回实时 cwd,并在 cwd 变化时像 title 一样广播 session 更新。(2) Android 端:单 session 增量广播(`onMonitorSession`)目前只就地更新卡片行,不触发重新分组;让 cwd 变化时走全量重渲染路径以重建分组。(3) 离线缓存:给磁盘缓存 Metadata 补 cwd 字段,缓存恢复时回填,避免离线会话全部落入"未同步目录"。

**Tech Stack:** Go 1.25(go-core)、go-headless-term v1.0.9(已内置 OSC 7 / `WorkingDirectoryPath()`)、Android Java(JVM junit + org.json 单测)、OkHttp。

## Global Constraints

- 不引入新 Go 依赖:`go-headless-term@v1.0.9` 已提供 `WorkingDirectoryPath()`,直接调用即可。
- 不修改第三方库源码(只读 `/Users/gao/go/pkg/mod/...` 作参考)。
- 服务器端 `terminal.cwd` 是创建时的绝对路径(`validateCWD` 已保证),OSC 7 上报的路径为空或解析失败时必须回退到该初始值,不得返回空串(否则分组退化为"未同步目录")。
- Android 端 `cwd` 唯一消费方是 `SessionRecyclerAdapter.buildGroupedItems`(`session.optString("cwd")`);不要把 cwd 加入 `uiContent`(DiffUtil 已通过 groupKey 变化自动刷新分组标题,加入反而掩盖问题)。
- Go 测试用 `cd go-core && go test ./internal/session/...`;Android JVM 测试用 `cd android-client && ./gradlew app:testDebugUnitTest`(或仅跑指定类)。
- 每个任务结束前运行该任务的测试并提交一次。

---

## File Structure

**服务器端(go-core)**
- `go-core/internal/session/screen.go` — 修改:新增 `WorkingDirectoryPath()` 转发方法,暴露库的 OSC 7 解析结果。
- `go-core/internal/session/terminal.go` — 修改:`TerminalSession` 增加 `cwdChanged` 标记;`Info()` 用实时 cwd 覆盖;`PushOutput` 检测 cwd 变化并触发 `onTitleChanged` 回调(复用现有广播通道,避免新增回调签名)。
- `go-core/internal/session/terminal_test.go` — 修改:新增 OSC 7 cwd 更新测试。

**Android 端(android-client)**
- `android-client/app/src/main/java/com/webterm/mobile/ServerGroupController.java` — 修改:`upsertLocalSession` 在 cwd 变化时触发全量重渲染(`onRenderSessions`)而非就地更新行。
- `android-client/app/src/test/java/com/webterm/mobile/ServerGroupControllerRegroupTest.java` — 新建:JVM 单测验证 cwd 变化触发重分组。

**离线缓存(android-client)**
- `android-client/app/src/main/java/com/webterm/mobile/TerminalDiskCache.java` — 修改:`Metadata` 增加 `cwd` 字段及序列化。
- `android-client/app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java` — 修改:增加 cwd 字段,`snapshot`/`diskMetadata` 写入 cwd。
- `android-client/app/src/main/java/com/webterm/mobile/TerminalCacheCoordinator.java` — 修改:`Snapshot` 增加 cwd 字段。
- `android-client/app/src/main/java/com/webterm/mobile/CachedSessionMapper.java` — 修改:`toSessions` 回填 `cwd`。
- `android-client/app/src/main/java/com/webterm/mobile/SessionRowHelper.java` + `SessionRowActions.java` + `MainActivity.java` — 修改:`openSession` 链路传递 cwd 到运行态(供缓存使用)。

---

## Task 1: 服务器端暴露 OSC 7 实时工作目录

**Files:**
- Modify: `go-core/internal/session/screen.go`
- Test: `go-core/internal/session/terminal_test.go`

**Interfaces:**
- Produces: `ScreenState.WorkingDirectoryPath() string` — 返回 OSC 7 上报的路径(空串表示未上报)。后续 Task 2 的 `Info()` 消费它。

**背景:** `go-headless-term` 在收到 `OSC 7 ; file://hostname/path BEL/ST` 时调用 `Terminal.SetWorkingDirectory(uri)`,存入内部 `workingDir`,并通过 `Terminal.WorkingDirectoryPath()` 返回已解析的路径(如 `/home/user`)。`ScreenState` 目前只暴露了 `Write/Resize/Snapshot/Text/DirtyDelta`,未暴露 cwd。本任务只做转发。

- [ ] **Step 1: 写失败测试**

在 `go-core/internal/session/terminal_test.go` 末尾追加(注意:`screen` 字段未导出,测试在同 package `session` 内,可直接访问 `terminal.screen`):

```go
func TestTerminalSessionCwdUpdatesFromOSC7(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	initialCwd := terminal.Info().CWD
	if initialCwd == "" {
		t.Fatalf("initial CWD should not be empty")
	}

	// OSC 7 ; file://localhost/tmp BEL  —— 模拟 shell 上报新工作目录
	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))

	info := terminal.Info()
	if info.CWD != "/tmp" {
		t.Errorf("expected CWD to update to /tmp after OSC 7, got %q", info.CWD)
	}
}

func TestTerminalSessionCwdFallsBackWhenNoOSC7(t *testing.T) {
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	// 未发送任何 OSC 7,Info() 必须回退到初始 cwd(非空绝对路径)
	info := terminal.Info()
	if info.CWD == "" {
		t.Errorf("CWD must fall back to initial cwd when no OSC 7 received, got empty")
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd go-core && go test ./internal/session/ -run TestTerminalSessionCwd -v`
Expected: FAIL — `Info().CWD` 仍是初始值,`/tmp` 断言失败(此时还未改 `Info()`)。

- [ ] **Step 3: 在 ScreenState 暴露 WorkingDirectoryPath**

在 `go-core/internal/session/screen.go` 的 `ScreenState` 方法区(紧随 `Text()` 方法后)新增:

```go
// WorkingDirectoryPath returns the current working directory reported by the
// shell via OSC 7, parsed to a filesystem path. Returns empty string if the
// shell has not yet reported a directory.
func (screen *ScreenState) WorkingDirectoryPath() string {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.WorkingDirectoryPath()
}
```

- [ ] **Step 4: 让 Info() 用实时 cwd 覆盖**

在 `go-core/internal/session/terminal.go` 的 `Info()` 中,把 `CWD` 字段改为优先取实时值、回退初始值。修改第 125-145 行的 `Info()`:

```go
func (terminal *TerminalSession) Info() Info {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	cwd := terminal.cwd
	if screen := terminal.screen; screen != nil {
		if reported := screen.WorkingDirectoryPath(); reported != "" {
			cwd = reported
		}
	}
	return Info{
		ID:                terminal.id,
		InstanceID:        terminal.instance,
		Name:              terminal.name,
		TermTitle:         terminal.termTitle,
		DisplayTitle:      displayTitle(terminal.name, terminal.termTitle),
		CWD:               cwd,
		RecentInputLines:  []string{},
		RecentInputHidden: false,
		Command:           terminal.command,
		Status:            terminal.status,
		Clients:           len(terminal.clients),
		Cols:              terminal.cols,
		Rows:              terminal.rows,
		CreatedAt:         terminal.createdAt,
		LastActiveAt:      terminal.activeAt,
	}
}
```

注意:`Info()` 持有 `terminal.mu.RLock()`,而 `screen.WorkingDirectoryPath()` 内部获取的是 `screen.mu`(另一把锁),不会自死锁。这与 `Info()` 里没有调用 screen 的现状一致——但为安全起见,这里在 RLock 下调用 screen 方法是可行的,因为 screen 用独立锁。如果 review 时担心锁顺序,可在 RLock 外先取 `reported` 再构造 Info,但当前 screen.go 的所有方法都是自锁独立,无顺序问题。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd go-core && go test ./internal/session/ -run TestTerminalSessionCwd -v`
Expected: PASS — 两个新测试通过。

- [ ] **Step 6: 回归现有 session 测试**

Run: `cd go-core && go test ./internal/session/...`
Expected: PASS — 无回归。

- [ ] **Step 7: 提交**

```bash
git add go-core/internal/session/screen.go go-core/internal/session/terminal.go go-core/internal/session/terminal_test.go
git commit -m "feat(session): expose live cwd from OSC 7 in Info()"
```

---

## Task 2: 服务器端在 cwd 变化时广播 session 更新

**Files:**
- Modify: `go-core/internal/session/terminal.go`
- Test: `go-core/internal/session/terminal_test.go`

**Interfaces:**
- Consumes: Task 1 的 `ScreenState.WorkingDirectoryPath()`。
- Produces: `TerminalSession.PushOutput` 在 cwd 变化时调用 `onTitleChanged` 回调(该回调由 `manager.go:89` 注册,已实现 `broadcastManager({Type:"session", Data: term.Info()})`)。Android 端通过现有 `onMonitorSession` 通道接收。

**背景:** title 变更通过 `titleChanged` 布尔标记 + `PushOutput` 末尾触发 `onTitleChanged` 回调实现广播(`terminal.go:175-188`)。OSC 7 写入发生在同一次 `screen.Write` 中,但库的 `SetWorkingDirectory` 没有像 title provider 那样的回调钩子(只有 Middleware 可拦截,过于复杂)。改用「比较前后值」的最简方式:在 `PushOutput` 中比较 `WorkingDirectoryPath()` 前后值,变化则置标记,复用 `onTitleChanged` 广播通道。这样无需新增回调签名,manager 侧零改动。

- [ ] **Step 1: 写失败测试**

在 `go-core/internal/session/terminal_test.go` 追加:

```go
func TestTerminalSessionBroadcastsOnCwdChange(t *testing.T) {
	var broadcastCount int
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "s1",
		CWD:     ".",
		Command: "/bin/sh",
		OnTitle: func() {
			broadcastCount++
		},
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	// OSC 7 上报新目录,应触发一次广播(OnTitle 回调)
	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))
	if broadcastCount < 1 {
		t.Errorf("expected OnTitle broadcast to fire on cwd change, got %d", broadcastCount)
	}

	// 再次上报相同目录,不应重复广播
	before := broadcastCount
	terminal.PushOutput([]byte("\x1b]7;file://localhost/tmp\x07"))
	if broadcastCount != before {
		t.Errorf("expected no extra broadcast when cwd unchanged, got delta %d", broadcastCount-before)
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd go-core && go test ./internal/session/ -run TestTerminalSessionBroadcastsOnCwdChange -v`
Expected: FAIL — `broadcastCount` 为 0,OSC 7 不会触发 `OnTitle`。

- [ ] **Step 3: 在 PushOutput 中检测 cwd 变化并复用广播**

修改 `go-core/internal/session/terminal.go` 的 `PushOutput`(原第 175-188 行)。在结构体中新增 `cwdChanged bool` 字段(加在 `titleChanged` 旁,第 55 行附近),然后改 `PushOutput`:

先加字段(在 `TerminalSession` 结构体中,`titleChanged bool` 后一行):
```go
	titleChanged   bool
	cwdChanged     bool
```

再改 `PushOutput`,在 push 后比较 cwd:

```go
func (terminal *TerminalSession) PushOutput(data []byte) EventFrame {
	terminal.mu.Lock()
	prevCwd := ""
	if screen := terminal.screen; screen != nil {
		prevCwd = screen.WorkingDirectoryPath()
	}
	frame := terminal.pushOutputLocked(data)
	changed := terminal.titleChanged
	terminal.titleChanged = false
	if screen := terminal.screen; screen != nil {
		if cur := screen.WorkingDirectoryPath(); cur != prevCwd && cur != "" {
			terminal.cwdChanged = true
		}
	}
	cwdChanged := terminal.cwdChanged
	terminal.cwdChanged = false
	onTitleChanged := terminal.onTitleChanged
	terminal.mu.Unlock()

	if (changed || cwdChanged) && onTitleChanged != nil {
		onTitleChanged()
	}
	return frame
}
```

> 注意:`pushOutputLocked` 内部调用 `screen.Write`,OSC 7 在此期间被解析写入 `workingDir`。我们在调用前后各取一次 `WorkingDirectoryPath()` 比较即可。两处取值都在 `terminal.mu.Lock()` 下,`screen.WorkingDirectoryPath()` 用的是 `screen.mu` 独立锁,无死锁。`cur != ""` 防止 shell 清空目录时报空串误触发。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd go-core && go test ./internal/session/ -run TestTerminalSessionBroadcastsOnCwdChange -v`
Expected: PASS。

- [ ] **Step 5: 回归 session 全部测试**

Run: `cd go-core && go test ./internal/session/...`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add go-core/internal/session/terminal.go go-core/internal/session/terminal_test.go
git commit -m "feat(session): broadcast session update on cwd change via OSC 7"
```

---

## Task 3: Android 端 cwd 变化时触发重新分组

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/ServerGroupController.java`
- Create: `android-client/app/src/test/java/com/webterm/mobile/ServerGroupControllerRegroupTest.java`

**Interfaces:**
- Consumes: 服务器 `onMonitorSession` 增量广播的 session JSON(含 `cwd` 字段)。
- Produces: `upsertLocalSession` 在 cwd 变化时调用 `listener.onRenderSessions(server, lastSessions, subList)` 全量重渲染,使 `SessionRecyclerAdapter.buildGroupedItems` 重新按 cwd 分组。

**背景:** 当前 `upsertLocalSession`(`ServerGroupController.java:138-176`)在收到单 session 更新时,只对已存在的行 `SessionRowHelper.updateSessionRow` 就地刷新,从不调 `onRenderSessions`。`buildGroupedItems` 只在 `submitSessions`/`onRenderSessions` 时执行,所以 cwd 变了分组也不会动。修复:比较新旧 session 的 `cwd`,变化时走重渲染路径。

- [ ] **Step 1: 写失败测试**

新建 `android-client/app/src/test/java/com/webterm/mobile/ServerGroupControllerRegroupTest.java`:

```java
package com.webterm.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ServerGroupControllerRegroupTest {

    static class FakeListener implements ServerGroupController.Listener {
        JSONArray lastRendered;
        int renderCount = 0;
        @Override public boolean isActive(ServerGroupController controller) { return true; }
        @Override public void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
            lastRendered = sessions;
            renderCount++;
        }
        @Override public void onSessionClosed(String baseUrl, String sessionId) {}
        @Override public void onScheduleFallbackRefresh() {}
    }

    // ServerGroupController.upsertLocalSession 是 private;通过反射或包内可见性调用。
    // 这里改用一个轻量包装:直接对 upsertLocalSession 做行为验证。
    @Test
    public void cwdChangeTriggersRerender() throws Exception {
        ServerConfig server = ServerConfig.fromUrl("https://example.com");
        FakeListener listener = new FakeListener();
        // subList / status / activity 在 JVM 测试中无法实例化真实 View,
        // 但 upsertLocalSession 只在 UI 线程分发后才触碰 View,核心逻辑可被验证。
        // 为绕开构造器对 Activity/View 的依赖,用反射创建实例并注入字段。
        ServerGroupController controller = newServerGroupController(server, listener);

        JSONObject s1 = new JSONObject().put("id", "mac:s1").put("cwd", "/home/user");
        invokeUpsert(controller, s1);

        // 同 id,cwd 改变
        JSONObject s1Moved = new JSONObject().put("id", "mac:s1").put("cwd", "/tmp");
        invokeUpsert(controller, s1Moved);

        assertTrue("cwd change should trigger onRenderSessions rerender", listener.renderCount >= 1);
        if (listener.lastRendered != null) {
            assertEquals("/tmp", listener.lastRendered.optJSONObject(0).optString("cwd"));
        }
    }

    @Test
    public void sameCwdDoesNotTriggerRerender() throws Exception {
        ServerConfig server = ServerConfig.fromUrl("https://example.com");
        FakeListener listener = new FakeListener();
        ServerGroupController controller = newServerGroupController(server, listener);

        JSONObject s1 = new JSONObject().put("id", "mac:s1").put("cwd", "/home/user");
        invokeUpsert(controller, s1);
        int afterFirst = listener.renderCount;

        JSONObject s1Same = new JSONObject().put("id", "mac:s1").put("cwd", "/home/user").put("termTitle", "zsh");
        invokeUpsert(controller, s1Same);

        assertEquals("same cwd must not rerender (only in-place row update)",
            afterFirst, listener.renderCount);
    }

    // ---- 反射辅助:绕过构造器对 Android View 的依赖 ----
    private static ServerGroupController newServerGroupController(ServerConfig server, FakeListener listener)
            throws Exception {
        // 构造器需要 Activity/LinearLayout/StatusIndicatorView,JVM 单测里给 null,
        // 因为被测路径(upsertLocalSession)在 runOnUiThread 分发前不触碰这些字段。
        ServerGroupController c = (ServerGroupController) sun.misc.Unsafe.class.getDeclaredMethod("getUnsafe").invoke(null);
        // 上行仅为占位;实际用反射分配实例:
        java.lang.reflect.Constructor<ServerGroupController> ctor = ServerGroupController.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterTypes().length];
        // server 是第 3 个参数(index 2);其余 null
        args[2] = server;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null && ctor.getParameterTypes()[i] == ServerConfig.class) args[i] = server;
        }
        ServerGroupController controller = ctor.newInstance(args);
        // 注入 listener 与 lastSessions
        java.lang.reflect.Field f = ServerGroupController.class.getDeclaredField("listener");
        f.setAccessible(true); f.set(controller, listener);
        java.lang.reflect.Field ls = ServerGroupController.class.getDeclaredField("lastSessions");
        ls.setAccessible(true); ls.set(controller, new JSONArray());
        return controller;
    }

    private static void invokeUpsert(ServerGroupController controller, JSONObject session) throws Exception {
        java.lang.reflect.Method m = ServerGroupController.class.getDeclaredMethod("upsertLocalSession", JSONObject.class);
        m.setAccessible(true);
        m.invoke(controller, session);
    }
}
```

> 注:上面的反射构造含一行无效 `sun.misc.Unsafe` 占位(应删除)。最终干净版本见 Step 3,这里先写出来以便先看到失败。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.ServerGroupControllerRegroupTest"`
Expected: 编译失败或测试失败。`upsertLocalSession` 当前 cwd 变化时不调 `onRenderSessions`,`sameCwdDoesNotTriggerRerender` 会通过但 `cwdChangeTriggersRerender` 失败(renderCount=0)。

- [ ] **Step 3: 清理测试文件并实现 upsertLocalSession 的 cwd 重分组**

先把测试文件的 `newServerGroupController` 中那行无效 `sun.misc.Unsafe` 占位删除,保持反射构造干净。

然后修改 `android-client/app/src/main/java/com/webterm/mobile/ServerGroupController.java` 的 `upsertLocalSession`(原第 138-176 行)。在更新 `lastSessions` 时记录旧 cwd,变化时走重渲染:

```java
    private void upsertLocalSession(JSONObject newData) {
        if (lastSessions == null) {
            lastSessions = new JSONArray();
        }
        String id = newData.optString("id");
        if (id == null) return;

        String newCwd = newData.optString("cwd", "");
        String oldCwd = "";
        boolean found = false;
        for (int i = 0; i < lastSessions.length(); i++) {
            JSONObject session = lastSessions.optJSONObject(i);
            if (session != null && id.equals(session.optString("id"))) {
                oldCwd = session.optString("cwd", "");
                try {
                    lastSessions.put(i, newData);
                } catch (JSONException ignored) {
                }
                found = true;
                break;
            }
        }
        if (!found) {
            lastSessions.put(newData);
        }
        final boolean insertedNew = !found;
        final boolean cwdChanged = found && !oldCwd.equals(newCwd);

        final String rawTermTitle = newData.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = newData.optString("name", "").trim();
        Log.i(TAG, "TitleTrace upsert row id=" + id + " termTitle=" + termTitle + " name=" + nameText + " active=" + listener.isActive(this));
        activity.runOnUiThread(() -> {
            if (cwdChanged && listener.isActive(this)) {
                // cwd 变化意味着分组归属可能改变,必须重建分组列表,而非就地刷新单行。
                listener.onRenderSessions(server, lastSessions, subList);
                return;
            }
            View rowView = activity.findViewById(android.R.id.content).findViewWithTag(id);
            if (rowView != null) {
                if (activity instanceof SessionRowActions) {
                    SessionRowHelper.updateSessionRow((SessionRowActions) activity, rowView, newData, server);
                }
            } else if (insertedNew && listener.isActive(this)) {
                listener.onRenderSessions(server, lastSessions, subList);
            }
        });
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.ServerGroupControllerRegroupTest"`
Expected: PASS — 两个测试均通过。

> 如果反射构造 `ServerGroupController` 在 JVM 测试环境因缺少 Android stub 报错,可改为:把 `upsertLocalSession` 的 cwd 比较逻辑抽成一个 `static` 纯函数 `static boolean shouldRerenderForCwd(JSONObject oldSession, JSONObject newData)`,测试该纯函数,避免反射构造整个控制器。若 Step 3 的反射方案跑不通,改用此纯函数方案(见下方备选)。

**备选纯函数方案(若反射方案不可行):**

在 `ServerGroupController` 中加:
```java
static boolean shouldRerenderForCwd(JSONObject oldSession, JSONObject newData) {
    if (oldSession == null || newData == null) return false;
    String id = newData.optString("id");
    if (id == null || !id.equals(oldSession.optString("id"))) return false;
    return !oldSession.optString("cwd", "").equals(newData.optString("cwd", ""));
}
```
测试改为直接断言 `ServerGroupController.shouldRerenderForCwd(old, moved)` 为 true、同 cwd 为 false。`upsertLocalSession` 内部调用该函数决定走哪条分支。

- [ ] **Step 5: 回归 Android 单测**

Run: `cd android-client && ./gradlew app:testDebugUnitTest`
Expected: PASS — 无回归。

- [ ] **Step 6: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/ServerGroupController.java android-client/app/src/test/java/com/webterm/mobile/ServerGroupControllerRegroupTest.java
git commit -m "fix(android): rerender session groups when cwd changes"
```

---

## Task 4: 离线缓存 Metadata 增加 cwd 字段

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/TerminalDiskCache.java`
- Create: `android-client/app/src/test/java/com/webterm/mobile/TerminalDiskCacheCwdTest.java`

**Interfaces:**
- Produces: `TerminalDiskCache.Metadata.cwd` 字段 + JSON 序列化(`toJson`/`fromJson` 读写 `"cwd"` 键)。后续 Task 5/6/7 消费。

**背景:** 当前 `Metadata`(`TerminalDiskCache.java:267-324`)无 cwd,`toJson()` 不写、`fromJson()` 不读。离线恢复时 `CachedSessionMapper.toSessions` 生成的 session 无 cwd → 全落"未同步目录"。本任务先给存储层加字段。

- [ ] **Step 1: 写失败测试**

新建 `android-client/app/src/test/java/com/webterm/mobile/TerminalDiskCacheCwdTest.java`:

```java
package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class TerminalDiskCacheCwdTest {

    @Test
    public void metadataRoundTripsCwd() throws Exception {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.baseUrl = "https://example.com";
        meta.sessionId = "s1";
        meta.instanceId = "inst1";
        meta.createdAt = "2026-07-02T00:00:00Z";
        meta.cwd = "/home/user/projects";

        JSONObject json = meta.toJson();

        TerminalDiskCache.Metadata restored = TerminalDiskCache.Metadata.fromJson(json);
        assertEquals("/home/user/projects", restored.cwd);
    }

    @Test
    public void fromJsonFallsBackToEmptyCwdForOldCache() throws Exception {
        // 旧缓存无 cwd 键,应回退为空串而非崩溃
        JSONObject json = new JSONObject()
            .put("baseUrl", "https://example.com")
            .put("sessionId", "s1")
            .put("instanceId", "")
            .put("createdAt", "")
            .put("termTitle", "zsh");

        TerminalDiskCache.Metadata restored = TerminalDiskCache.Metadata.fromJson(json);
        assertEquals("", restored.cwd);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.TerminalDiskCacheCwdTest"`
Expected: FAIL — `meta.cwd` 字段不存在,编译失败。

- [ ] **Step 3: 给 Metadata 加 cwd 字段与序列化**

修改 `android-client/app/src/main/java/com/webterm/mobile/TerminalDiskCache.java`。

(a) 在 `Metadata` 字段区(第 267-278 行)加 `cwd`:
```java
    static final class Metadata {
        String baseUrl = "";
        String sessionId = "";
        String instanceId = "";
        String createdAt = "";
        String termTitle = "";
        String sessionName = "";
        String cwd = "";
        int columns;
        int rows;
        long lastSeq;
        long updatedAt;
```

(b) 在拷贝构造器(第 282-293 行)加:
```java
        Metadata(Metadata other) {
            baseUrl = other.baseUrl;
            sessionId = other.sessionId;
            instanceId = other.instanceId;
            createdAt = other.createdAt;
            termTitle = other.termTitle;
            sessionName = other.sessionName;
            cwd = other.cwd;
            columns = other.columns;
            rows = other.rows;
            lastSeq = other.lastSeq;
            updatedAt = other.updatedAt;
        }
```

(c) 在 `toJson()`(第 295-308 行)`sessionName` 后加:
```java
            json.put("sessionName", sessionName);
            json.put("cwd", cwd);
            json.put("cols", columns);
```

(d) 在 `fromJson()`(第 310-323 行)加:
```java
            metadata.sessionName = json.optString("sessionName", "");
            metadata.cwd = json.optString("cwd", "");
            metadata.columns = json.optInt("cols", 0);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.TerminalDiskCacheCwdTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/TerminalDiskCache.java android-client/app/src/test/java/com/webterm/mobile/TerminalDiskCacheCwdTest.java
git commit -m "feat(android): persist cwd in terminal disk cache metadata"
```

---

## Task 5: 运行态与缓存协调器传递 cwd

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/TerminalCacheCoordinator.java`

**Interfaces:**
- Consumes: Task 4 的 `Metadata.cwd`。
- Produces: `TerminalRuntimeState` 持有 cwd 并在 `diskMetadata()` 写入;`TerminalCacheCoordinator.Snapshot` 持有 cwd 透传给缓存写入。

**背景:** `TerminalRuntimeState`(`TerminalRuntimeState.java`)目前无 cwd,`diskMetadata()` 不写 cwd。`snapshot()` 产出 `TerminalCacheCoordinator.Snapshot`,后者被缓存写入流程消费。本任务把 cwd 接入运行态与快照。

- [ ] **Step 1: 检查 TerminalCacheCoordinator.Snapshot 现有字段**

Run: `grep -n "class Snapshot\|String \|String " android-client/app/src/main/java/com/webterm/mobile/TerminalCacheCoordinator.java | head -30`
记录 `Snapshot` 的字段列表(预期含 baseUrl/cookie/sessionId/instanceId/termTitle/sessionName/createdAt 等)。

- [ ] **Step 2: 给 Snapshot 加 cwd 字段**

在 `TerminalCacheCoordinator.java` 的 `Snapshot` 类字段区,与其他 `String` 字段并列新增:
```java
        String cwd = "";
```
(字段名与初始值风格对齐已有字段;若该类用 `String xxx;` 无初始化,则跟随其风格。)

- [ ] **Step 3: 给 TerminalRuntimeState 加 cwd 字段与写入**

修改 `android-client/app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java`。

(a) 字段区(第 8-16 行附近)加:
```java
    private String cwd = "";
```

(b) 加访问器(与 `instanceId()` 等并列):
```java
    String cwd() {
        return cwd;
    }

    void setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd;
    }
```

(c) 在 `clearTerminalDetails()`(第 103-109 行)加清空:
```java
    void clearTerminalDetails() {
        instanceId = "";
        createdAt = "";
        cwd = "";
        columns = 0;
        rows = 0;
        lastSeq = 0;
    }
```

(d) 在 `snapshot()`(第 114-129 行)写入 Snapshot:
```java
        snapshot.cwd = cwd;
        snapshot.diskMetadata = diskMetadata(titleView, subtitleView);
```

(e) 在 `diskMetadata()`(第 131-144 行)写入 Metadata:
```java
        metadata.sessionName = subtitleView == null ? "" : String.valueOf(subtitleView.getText());
        metadata.cwd = cwd;
        metadata.columns = columns;
```

- [ ] **Step 4: 编译确认**

Run: `cd android-client && ./gradlew app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL — 若 `Snapshot` 字段初始化风格不符会报错,按 Step 2 调整。

- [ ] **Step 5: 回归 Android 单测**

Run: `cd android-client && ./gradlew app:testDebugUnitTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java android-client/app/src/main/java/com/webterm/mobile/TerminalCacheCoordinator.java
git commit -m "feat(android): carry cwd through runtime state and cache snapshot"
```

---

## Task 6: openSession 链路传递 cwd 到运行态

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/SessionRowActions.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/SessionRowHelper.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java`

**Interfaces:**
- Consumes: Task 5 的 `TerminalRuntimeState.setCwd(String)`。
- Produces: 点击卡片启动终端时,把 session JSON 里的 `cwd` 一路传到 `terminalState.setCwd(...)`,使后续磁盘缓存写入带 cwd。

**背景:** `SessionRowHelper.updateSessionRow` 第 112 行点击回调 `actions.openSession(server, id, termTitle, nameText, createdAt, instanceId)` 漏传 cwd;`openSession` → `showTerminal` → `TerminalLifecycleController.showTerminal` 链路也没有 cwd 参数。本任务把 cwd 接进签名链。`MainActivity.java` 有 5 个 `showTerminal` 重载,只改最底层的那个和 `openSession`。

- [ ] **Step 1: 扩展 SessionRowActions 接口加 cwd**

修改 `android-client/app/src/main/java/com/webterm/mobile/SessionRowActions.java`:
```java
interface SessionRowActions {
    void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId, String cwd);
}
```

- [ ] **Step 2: SessionRowHelper 点击回调传 cwd**

修改 `android-client/app/src/main/java/com/webterm/mobile/SessionRowHelper.java` 第 112 行:
```java
        final String cwd = session.optString("cwd", "").trim();
        row.setOnClickListener((v) -> actions.openSession(server, id, termTitle, nameText, createdAt, instanceId, cwd));
```

- [ ] **Step 3: MainActivity.openSession 与 showTerminal 传 cwd**

修改 `android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`:

(a) `openSession`(第 478-482 行)加 cwd 参数并传给 `showTerminal`:
```java
    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName,
                            String createdAt, String instanceId, String cwd) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, sessionName, createdAt, instanceId, server.isRelayDevice(), server.getDeviceId(), cwd);
    }
```

(b) 最底层 `showTerminal`(第 465-475 行)加 cwd 参数并传给 `TerminalLifecycleController`:
```java
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId, boolean relayDevice, String relayDeviceId, String cwd) {
        if (mHomeCoordinator != null) mHomeCoordinator.pause();
        mScreenMode = ScreenMode.TERMINAL;
        mTerminalLifecycle.showTerminal(
            baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId, relayDeviceId, cwd,
            this, mTerminalSessionClient,
            this::showSessionListOrDeviceHome
        );
        startRelayDeviceConnection(baseUrl, cookie, relayDeviceId);
    }
```

(c) 其余 4 个 `showTerminal` 重载(第 450-463 行)在转发时补 `""` 作为 cwd 默认值,例如:
```java
    void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "", "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, String createdAt) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, "", false, "", "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId, boolean relayDevice) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId, relayDevice, "", "");
    }
```

(d) 第 73 行那处 `showTerminal(...)` 调用(无 cwd 的旧调用点)按编译器提示补齐 `""` 参数。

- [ ] **Step 4: TerminalLifecycleController.showTerminal 接收 cwd 并写入运行态**

修改 `android-client/app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java`:

(a) 签名(第 96-103 行)加 `String cwd`:
```java
    void showTerminal(
        String baseUrl, String cookie, String sessionId,
        String termTitle, String sessionName, String createdAt, String instanceId,
        String relayDeviceId, String cwd,
        WebTermTerminalViewClient.Host viewClientHost,
        WebTermTerminalSessionClient sessionClient,
        Runnable onBack
    ) {
```

(b) 在 `terminalState.setServerSession(...)` 之后(第 111 行后)写入 cwd:
```java
        terminalState.setServerSession(baseUrl, cookie, sessionId, relayDeviceId);
        terminalState.setCwd(cwd == null ? "" : cwd);
```

- [ ] **Step 5: 编译确认**

Run: `cd android-client && ./gradlew app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL。若报「方法签名不匹配」之类,定位其余 `showTerminal`/`openSession` 调用点按提示补参。

- [ ] **Step 6: 检查 ServerGroupController.java 第 170 行的 updateSessionRow 调用**

`ServerGroupController.java:170` 也调用 `SessionRowHelper.updateSessionRow`,但 `updateSessionRow` 签名未变(它内部自己从 session 取 cwd),无需改动。确认无其他 `openSession` 实现类。

Run: `grep -rn "implements SessionRowActions\|openSession(" android-client/app/src/main/java/com/webterm/mobile/`
Expected: 仅 `MainActivity` 实现 `SessionRowActions`;若有其他实现类需同步加 cwd 参数。

- [ ] **Step 7: 回归 Android 单测**

Run: `cd android-client && ./gradlew app:testDebugUnitTest`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/SessionRowActions.java android-client/app/src/main/java/com/webterm/mobile/SessionRowHelper.java android-client/app/src/main/java/com/webterm/mobile/MainActivity.java android-client/app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java
git commit -m "feat(android): thread cwd from session card click into runtime state"
```

---

## Task 7: 缓存映射器回填 cwd

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/CachedSessionMapper.java`
- Create: `android-client/app/src/test/java/com/webterm/mobile/CachedSessionMapperCwdTest.java`

**Interfaces:**
- Consumes: Task 4 的 `Metadata.cwd`。
- Produces: `CachedSessionMapper.toSessions` 输出的 session JSON 含 `cwd`,供离线恢复时 `SessionRecyclerAdapter.buildGroupedItems` 分组。

**背景:** `CachedSessionMapper.toSessions`(`CachedSessionMapper.java:10-28`)把 `Metadata` 列表转成 session JSONArray,当前不写 cwd。离线恢复走 `HomeServerCoordinator.java:224-225` 的 `OFFLINE_CACHE` 分支调 `renderServerSessions` → `submitSessions`,此时 session 无 cwd → 全落"未同步目录"。

- [ ] **Step 1: 写失败测试**

新建 `android-client/app/src/test/java/com/webterm/mobile/CachedSessionMapperCwdTest.java`:

```java
package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

public class CachedSessionMapperCwdTest {

    @Test
    public void toSessionsIncludesCwd() {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.sessionId = "s1";
        meta.instanceId = "inst1";
        meta.sessionName = "work";
        meta.termTitle = "zsh";
        meta.createdAt = "2026-07-02T00:00:00Z";
        meta.cwd = "/home/user/projects";

        JSONArray sessions = CachedSessionMapper.toSessions(java.util.Collections.singletonList(meta));

        assertEquals(1, sessions.length());
        assertEquals("/home/user/projects", sessions.optJSONObject(0).optString("cwd"));
    }

    @Test
    public void toSessionsEmitsEmptyCwdWhenMissing() {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.sessionId = "s1";
        // cwd 不设置(默认 "")

        JSONArray sessions = CachedSessionMapper.toSessions(java.util.Collections.singletonList(meta));

        assertEquals("", sessions.optJSONObject(0).optString("cwd"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.CachedSessionMapperCwdTest"`
Expected: FAIL — `toSessions` 不写 cwd,`optString("cwd")` 返回 `""`,第一个测试断言 `/home/user/projects` 失败。

- [ ] **Step 3: 在 toSessions 写入 cwd**

修改 `android-client/app/src/main/java/com/webterm/mobile/CachedSessionMapper.java` 的 `toSessions`,在 `session.put("createdAt", ...)` 后加:
```java
                session.put("createdAt", meta.createdAt);
                session.put("cwd", meta.cwd);
                session.put("cols", meta.columns);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android-client && ./gradlew app:testDebugUnitTest --tests "com.webterm.mobile.CachedSessionMapperCwdTest"`
Expected: PASS。

- [ ] **Step 5: 回归全部 Android 单测**

Run: `cd android-client && ./gradlew app:testDebugUnitTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/CachedSessionMapper.java android-client/app/src/test/java/com/webterm/mobile/CachedSessionMapperCwdTest.java
git commit -m "feat(android): restore cwd from cache into session list for offline regroup"
```

---

## Task 8: 端到端冒烟验证(服务器 + Android)

**Files:**
- 无代码改动;运行现有冒烟脚本并手动核对分组刷新。

**背景:** 前 7 个任务改了服务器 cwd 上报/广播、Android 重分组、离线缓存。本任务用一个真实 shell(支持 OSC 7 的 zsh/bash 需 shell 集成;若无集成则验证回退路径)核对在线分组随 `cd` 刷新,以及离线恢复分组正确。

> 注意:OSC 7 默认只有配置了 shell 集成的 zsh(如 macOS 默认 zsh + `printf '\e]7;file://.../%s\a' "$PWD"` 在 `chpwd` 钩子)或安装了相应插件的 bash 才会主动上报。若测试 shell 不上报 OSC 7,服务器 cwd 保持初始值(创建时 cwd),这是预期回退行为,不是 bug。冒烟脚本应使用会主动发 OSC 7 的 shell 集成,或手动 `printf '\e]7;file://localhost/tmp\a'` 验证。

- [ ] **Step 1: 跑服务器冒烟脚本**

Run: `cd go-core && go run ./cmd/webterm-flow-smoke -cwd /tmp`
Expected: 脚本创建会话并查询 `/api/sessions`,返回 JSON 中 `cwd` 字段为 `/tmp` 或 OSC 7 上报值。

- [ ] **Step 2: 跑 Go relay 冒烟脚本(若适用)**

Run: `cd go-core && go run ./cmd/webterm-relay-e2e-smoke -cwd /tmp`
Expected: 通过,cwd 字段正确。

- [ ] **Step 3: 服务器 + Android 联调(手动)**

1. 启动 go-core 服务器。
2. Android 客户端连接,创建会话。
3. 在终端执行 `cd /tmp`(使用支持 OSC 7 的 shell,或手动 `printf '\e]7;file://localhost/tmp\a'`)。
4. 观察:首页终端卡片应从原分组移到 `/tmp` 分组,**无需手动下拉刷新**。
5. 退出终端、断网,冷启动 App 走离线缓存:卡片分组仍按各自 cwd 显示,不全落"未同步目录"。

- [ ] **Step 4: 若 Step 3 分组不刷新,检查广播链路**

服务器日志应有 session 广播;Android `ServerGroupController` 的 `TitleTrace` 日志应显示 upsert 且 `cwdChanged=true` 触发 `onRenderSessions`。若 `cwdChanged` 始终 false,说明服务器未广播 cwd 变化 → 回到 Task 2 排查;若广播了但未重渲染 → 回到 Task 3 排查。

- [ ] **Step 5: 全量回归测试**

Run: `cd go-core && go test ./...`
Run: `cd android-client && ./gradlew app:testDebugUnitTest`
Expected: 全绿。

- [ ] **Step 6: 提交(若有验证文档)**

```bash
git add docs/  # 若记录了验证结果
git commit -m "test: verify cwd-based session grouping end to end"
```

---

## Self-Review

**1. Spec coverage:**
- 在线分组全落"未同步目录" → Task 1(服务器下发改初始 cwd)+ Task 6(Android 创建时… 实际 Android 创建不传 cwd,服务器用 `os.Getwd()`;Task 1 让 Info 返回该初始值,至少不再是空)覆盖。注:Android 创建会话不传 cwd 是次要问题,服务器回退到 `os.Getwd()` 仍是非空绝对路径,Task 1 保证它被正确下发。
- 在线 `cd` 后分组不刷新 → Task 1(实时 cwd)+ Task 2(广播)+ Task 3(Android 重分组)覆盖。✓
- 离线恢复全落"未同步目录" → Task 4/5/6/7 覆盖。✓
- 用户原分析中的"Diff 忽略 cwd"(第①条)→ 经核查非根因,计划明确不改 `uiContent`(见 Global Constraints),避免引入无效改动。✓

**2. Placeholder scan:** 计划中无 TBD/TODO;每步含具体代码或具体命令。Task 3 的反射测试有「备选纯函数方案」兜底,非占位。

**3. Type consistency:**
- `ScreenState.WorkingDirectoryPath()`(Task 1)→ `Info()` 与 `PushOutput`(Task 2)均调用同名方法。✓
- `Metadata.cwd`(Task 4)→ `TerminalRuntimeState.diskMetadata` 写入(Task 5)→ `CachedSessionMapper.toSessions` 读取(Task 7)→ `Metadata.fromJson` 读取(Task 4)。字段名统一 `cwd`。✓
- `TerminalRuntimeState.setCwd`/`cwd()`(Task 5)→ `TerminalLifecycleController.showTerminal` 调用 `setCwd`(Task 6)。✓
- `openSession(..., String cwd)`(Task 6 接口)→ `MainActivity.openSession` 实现(Task 6)→ `SessionRowHelper` 调用(Task 6)。签名一致。✓
- `Snapshot.cwd`(Task 5)与 `Metadata.cwd`(Task 4)是两个不同类的字段,各自独立,无冲突。✓

## Execution Handoff

计划已保存到 `docs/superpowers/plans/2026-07-02-terminal-cwd-grouping.md`。两种执行方式:

**1. Subagent-Driven(推荐)** — 每个任务派发独立子 agent,任务间审查,迭代快。

**2. Inline Execution** — 在当前会话按 executing-plans 批量执行,带检查点。

选哪种?
