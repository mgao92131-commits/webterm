# WebTerm 代码审查报告

> 审查日期：2026-06-20
> 审查范围：当前分支全部未提交改动（Android 客户端、Vue 前端、Relay 服务器、Agent 服务器、共享协议）

---

## 整体概述

本次改动涉及 5 个模块，核心主题有三项：

1. **前端移动端 UI 重构** — 顶部下拉设备面板 + Popover 更多菜单
2. **Android 会话列表按目录分组** — RecyclerView 分组折叠 + 移除旧的 ServerGroup 渲染
3. **终端状态恢复机制重构** — 从客户端磁盘快照改为服务端 `MSG_STATE` 协议推送 + OTP/Cookie 认证流程

---

## 一、前端 ManagerView.vue

### 做得好的地方

- 用 `<Teleport to="body">` 渲染 Popover，避开了父级 `overflow` / `z-index` 裁剪问题
- `handleMediaChange` 监听 `matchMedia`，从 mobile 切回 desktop 时自动关闭面板，防止残留状态
- 保留了"选择在线设备"引导按钮，移动端无设备时交互路径清晰
- 设备选中后自动 `isPanelOpen = false` 收起面板，体验流畅
- 桌面端设备侧栏用 `hidden md:flex` 隐藏，移动端面板用 `md:hidden`，互不干扰

### 存在的问题

#### 1. Popover 透明遮罩可能阻断设备面板交互（中）

```html
<div v-if="isMenuOpen" class="fixed inset-0 z-50 bg-transparent" @click="isMenuOpen = false"></div>
```

如果用户先打开了设备面板（`isPanelOpen = true`），又点击 `MoreVertical` 打开 Popover（`isMenuOpen = true`），Popover 的 `fixed inset-0 z-50` 透明遮罩会覆盖在设备面板之上（面板 `z-20`），用户无法点击面板中的设备项。

**建议**：打开 Popover 时自动关闭设备面板：

```ts
// 在 MoreVertical 按钮的 click handler 中
isPanelOpen.value = false;
isMenuOpen.value = !isMenuOpen.value;
```

#### 2. CSS 过渡 `max-height` 与元素实际 `max-h-[300px]` 不一致（中）

CSS 中 `slide-down-enter-active` 设置 `max-height: 350px`，但面板元素 class 是 `max-h-[300px]`。动画目标值 (350px) 大于元素实际最大值 (300px)，动画后期会有"跳变"——内容在 300px 处被截断但动画还在向 350px 过渡。

**建议**：CSS 中改为 `max-height: 300px` 保持一致。

#### 3. 缺少 `overflow-hidden` 锁定背景滚动（低）

面板和 Popover 打开时，背后的主内容区（`<main>` 有 `overflow-y-auto`）仍可滚动。移动端标准做法是在面板打开时锁定 body 滚动。

**建议**：

```ts
watch([isPanelOpen, isMenuOpen], ([panel, menu]) => {
  document.body.style.overflow = (panel || menu) ? 'hidden' : '';
});
```

#### 4. `type="email"` 改为 `type="text"`（低）

`LoginView.vue` 中 `type="email"` → `type="text"` 会导致移动端键盘不显示 `@` 快捷键。如果是因为某些邮件地址格式不通过浏览器原生校验，更好的做法是保留 `type="email"` 并在 `<form>` 上加 `novalidate`。

---

## 二、Android SessionRecyclerAdapter（会话分组）

### 做得好的地方

- `DirectoryTitle.from()` 对边界情况处理到位（空 cwd、根目录、无分隔符）
- 分组按 `createdAt` 排序，逻辑正确
- 折叠/展开状态通过 `CollapseState` 接口解耦，设计合理
- `removeSession` 中自动清理空分组 header 并重置折叠状态，细节到位

### 存在的问题

#### 1. `CollapseState` 实现未在 diff 中出现（低）

`SessionRecyclerAdapter.CollapseState` 是一个接口，需要有具体实现（如 `MainActivity` 或 coordinator 中用 `HashMap<String, Boolean>` 实现）并通过 `setCollapseState()` 传入。diff 中未看到这部分代码，需确认是否遗漏。

#### 2. `removeSession` 中 `setGroupCollapsed(null, false)` 潜在 NPE（低）

当 `removed.groupKey` 为 `null` 时（旧数据的 session 没有 groupKey），`setGroupCollapsed(null, false)` 取决于 `CollapseState` 实现如何处理 null key。防御性加个 null 检查更安全。

---

## 三、Android TerminalLifecycleController（终端生命周期）

### 存在的问题

#### 1. `onState` 中重建 TerminalSession 会丢失之前的输出（低）

```java
void onState(long seq, byte[] data) {
    terminalSession = TerminalSession.createExternalSession(...);  // 全新实例
    terminalView.attachSession(terminalSession);
    terminalSession.appendOutput(data);
}
```

每次收到 `MSG_STATE` 消息都会创建全新的 `TerminalSession` 对象，之前所有输出和滚动历史都会丢失。服务端发来的 `state` 数据包含完整屏幕快照所以能恢复显示，但需确认：**服务端只在首次握手时发送一次 `MSG_STATE`，不会在正常会话中重复发送**，否则会导致终端闪烁。

#### 2. `onOutput` 强制 `runOnUiThread` 可能造成不必要的线程切换（低）

如果调用方已经在 UI 线程，`runOnUiThread` 仍会 post 到消息队列。可以加 `Looper.myLooper() == Looper.getMainLooper()` 判断来优化。

---

## 四、Android TerminalDiskCache（磁盘缓存重构）

### 评价

从客户端 GZip 快照迁移到服务端状态推送是**合理的架构决策**：
- 客户端不再需要处理序列化/反序列化的兼容性问题
- 服务端是状态的权威来源
- 减少了客户端的磁盘 I/O

### 存在的问题

#### 1. `RestoreResult.lastSeq` 硬编码为 0（低）

`saveMetadataOnly` 中 `metadata.lastSeq = 0`，`load()` 返回 `new RestoreResult(metadata, 0)`。当前行为正确（总是从 seq=0 请求重放），但如果未来想在元数据中保留 seq 用于调试，需要调整。

---

## 五、Android TerminalRenderer（字形渲染）

### 评价

这是一个**高质量的改进**。对 Powerline / Nerd Font / Emoji / 符号字符保持原始宽高比，避免被等宽字体拉伸变形。

### 小问题

`isPrivateUseGlyph` 覆盖范围过于宽泛（包括高位平面 `0xF0000-0xFFFFD` 和 `0x100000-0x10FFFD`），这些高位 PUA 字符通常不是图标字体。但实际使用中这些字符很少出现在终端中，影响可以忽略。

变量 `preserveGlyphPaddingColumn` 在 for 循环外声明但在循环内用于跨列状态传递，可读性稍差，建议重命名为 `nextPaddingColumn`。

---

## 六、认证流程（OTP + Cookie）

### 做得好的地方

- Cookie 合并逻辑（`parseAndMergeCookies`）正确处理了多 `Set-Cookie` 头的情况
- `refresh()` → `login()` 的降级链路清晰：先尝试 cookie refresh，失败则走密码登录
- `ExtendedLoginCallback` 接口设计合理，向后兼容

### 存在的问题

#### 1. `server/auth.js` 中的 debug 日志包含敏感信息（中）

```js
console.log('[Login Debug] Attempt:', { email, deviceId });
console.log('[Login Debug] User not found or disabled:', email);
// ... 多处
```

这些日志会在生产环境泄露用户邮箱。**必须移除或加环境变量开关**。

#### 2. OTP 验证码发送上限从 10 改为 60（中）

差异很大（10 → 60 次/天）。需确认是临时调试还是产品决策。如果是调试，上线前应改回合理值（如 10-20）。

---

## 七、协议层（MSG_STATE）

### 评价

新增 `MSG_STATE = 0x0a` 消息类型，用于区分"终端状态快照"和"实时输出"。协议设计合理，让客户端可以：
- 在收到 `MSG_STATE` 时重建终端会话
- 在收到 `MSG_OUTPUT` 时增量追加

测试覆盖完整（`protocol-binary.test.js` 和 `terminal-session.test.js` 均新增了相关测试用例），包括 hello 带尺寸参数 resize 后再序列化状态的场景。

### relay 代理路径修复

`relay-server/main.js` 和 `relay-server/routes.js` 中 WebSocket 与 HTTP 代理的 query string 处理做了互换修正——这是一个 bug fix，WebSocket 不需要 query string（参数走 tunnel 协议），HTTP 需要保留 query string。

`server/agent.js` 中 path 解析添加了 `split('?')[0]`，防止 relay 转发时带入 query string——防御性修复，合理。

---

## 八、`ws-handlers.js` 区分文本/二进制帧

```js
if (extraByte === WS_DATA_TEXT) {
  tunnel.clientWs.send(payload.toString('utf8'));
} else if (extraByte === WS_DATA_BINARY) {
  tunnel.clientWs.send(payload);
}
```

之前 relay 转发时所有 WebSocket 帧都按二进制发送。对于 JSON 子协议的客户端，二进制帧无法被正确解析。这个修复确保了 relay 能正确代理混合协议（二进制 + JSON）的 WebSocket 连接。

---

## 总结

| 模块 | 严重度 | 问题 |
|------|--------|------|
| 前端 | 🟡 中 | Popover 遮罩可能阻断设备面板交互 |
| 前端 | 🟡 中 | CSS 动画 `max-height` 与实际元素不一致 |
| 前端 | 🟢 低 | 缺少 body 滚动锁定 |
| 前端 | 🟢 低 | `type="email"` → `type="text"` 影响移动端键盘体验 |
| Android | 🟢 低 | `onState` 重建 TerminalSession 的语义需确认 |
| Android | 🟢 低 | `CollapseState` 实现未在 diff 中出现 |
| 服务端 | 🟡 中 | debug 日志含敏感信息（用户邮箱），需移除 |
| 服务端 | 🟡 中 | OTP 上限 10→60 需确认是否临时调试 |
| 整体 | ✅ | 架构方向正确：服务端状态推送替代客户端快照、RecyclerView 分组提升大列表性能、移动端响应式 UI 合理 |
