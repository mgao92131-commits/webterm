# 连接层统一实现计划（v2 · 审查修正版）

## 目标与原则

**连接层自治，视图层只开通道。**

P2P 的启动、回退、重连、切换全部在连接层内部完成。视图层（`MainActivity`/`TerminalConnection`/`ServerSessionMonitor`）只在已建立的连接上开/关通道，完全不碰传输细节，也不触发 P2P。

## 架构

```
视图层: TerminalConnection / ServerSessionMonitor
          │  只管：开通道(ws-connect) / 关通道 / 收发数据
   ┌──────┴──────┐
   │  MuxSession │  通道复用 + reopenChannels，依赖 MuxTransport 接口（不改）
   └──────┬──────┘
          │  MuxTransport：start / close / isConnected / sendText / sendBinary
   ┌──────┴─────────────────────────────┐
   │  CompositeTransport  ← 新增         │
   │  内部自治：选路 / 切换 / 重连       │
   │   ┌────────┐   ┌────────────────┐  │
   │   │ WS leg │   │ P2P leg + 协商  │  │
   │   │（始终） │   │（relay 才有）  │  │
   │   └────────┘   └────────────────┘  │
   └────────────────────────────────────┘
```

## CompositeTransport 设计

### 接口
实现 `MuxTransport`：`start(Listener)` / `close()` / `isConnected()` / `sendText()` / `sendBinary()`。对外行为和单 transport 一致，`MuxSession` 无需改动。

### 内部组成
- **WS leg**：`WebSocketMuxTransport`，始终存在，立即连。
- **P2P leg**：`WebRtcDataChannelTransport` + 协商器（复用 `P2PConnectionManager` 逻辑），仅 relay 设备（deviceId 非空）才有。
- **activeLeg**：当前承载数据的 leg（WS 或 P2P），`send` 走它。
- **pendingLeg**（**审查新增**）：切换中的目标 leg。通道在新 leg 上全部确认就绪后才提升为 activeLeg。
- **Listener**：转发给 `MuxSession` 的 `MuxTransport.Listener`。

### 状态机（审查修正版）

```
start:
  WS leg.start()
  if (relay 设备) 启动 P2P 协商（后台）

WS leg onOpen:
  if (activeLeg == null) { activeLeg = WS; listener.onOpen() }   ← 首次连上，通知 MuxSession

P2P 协商成功 + datachannel OPEN:
  pendingLeg = P2P
  listener.onOpen()                     ← 让 MuxSession reopenChannels，通道在新 leg 重开
  ← 等待 reopenChannels 中所有通道 ws-connected 确认
  ← 确认完毕后：停 WS leg（或保留作冷备），activeLeg = pendingLeg，pendingLeg = null

P2P 断（onDisconnected）:
  if (WS leg 活) {
    pendingLeg = WS
    listener.onOpen()                   ← 回退，重开通道
    ← 等待通道确认
    activeLeg = WS; pendingLeg = null
  } else {
    listener.onClosed(...)              ← 都断了，走重连
  }

WS 断:
  if (P2P leg 活 && activeLeg == P2P) { /* 已在 P2P，无感 */ }
  else if (P2P leg 活) { pendingLeg = P2P; listener.onOpen() → 确认 → activeLeg = P2P }
  else listener.onClosed(...)           ← 都断了

两条都断 → listener.onClosed → MuxSession.scheduleReconnect（自重连）
  → CompositeTransport.start() 重新启动 WS leg + 重试 P2P
```

### 切换的无感保证（关键）
切换 leg 时触发 `listener.onOpen()` → `MuxSession.onMuxConnected` → `reopenChannels`（重新 `ws-connect` 所有通道）→ `TerminalConnection.onConnected`：
- `state` 全程保持 `CONNECTED`，**不进 `RECONNECTING`**，指示灯不闪
- `sendHello` 带 `lastSeq` 续传，数据不丢
- 这是当前 `onConnected` 已有的行为，`CompositeTransport` 直接沿用

**审查修正**：增加 `pendingLeg` 过渡态，确保新 leg 通道全部就绪后才废弃旧 leg，避免双 leg 并存导致的通道冲突。

### 依赖
`CompositeTransport` 创建时需要：`OkHttpClient`、`Handler`、`baseUrl`、`cookie`、`deviceId`、`WebTermApi`（信令）、`PeerConnectionFactory`（全局单例，审查新增）。

## 依赖注入（审查简化版）

### PeerConnectionFactory 全局单例
在 `AppContainer` 中创建并持有 `PeerConnectionFactory`（`ensureFactory` 逻辑从 `P2PConnectionManager` 提升到此处）。避免 per-device 重复初始化。

### Context
`WebTermApplication` 增加静态 `getApplicationContext()` 方法。`CompositeTransport` 直接调用，**不经过 `AppContainer → Registry → Manager` 链**。

### WebTermApi 注入
`P2PConnectionManager` 改为接收 `WebTermApi` 参数（不再自己 `new WebTermApi(http)`），与 `AppContainer` 共享连接池。

### 汇总
```
AppContainer: 新增 peerConnectionFactory 字段
WebTermApplication: 新增静态 getApplicationContext()
P2PConnectionManager: 构造改为接收 (WebTermApi, PeerConnectionFactory, ...)
CompositeTransport: 创建时从 WebTermApplication 拿 context，从 RelayMuxSessionManager 拿 api/factory
```

## 视图层解耦（删除清单）

P2P 的启动/切换当前挂在视图动作上，全部删除：

| 删除项 | 位置 | 原因 |
|--------|------|------|
| `startP2PIfRelayDevice` | `MainActivity:528` | 视图不该触发 P2P |
| `mP2PConnectionManager` 字段 + 创建 | `MainActivity:38,96` | P2P 改由 CompositeTransport 持有 |
| `showSessionHome` 的 `mP2PConnectionManager.disconnect()` | `MainActivity:234` | 视图不该断 P2P |
| P2P `Listener`（`onConnected/onDisconnected → reconnectDevice`） | `MainActivity:89-103` | 切换下沉到连接层 |
| `relayMuxRegistry().setTransportProvider(...)` | `MainActivity:104` | CompositeTransport 内部管 P2P |
| `RelayMuxSessionRegistry.setTransportProvider` / `transportProvider` | `RelayMuxSessionRegistry` | 同上 |
| `RelayMuxSessionManager.reconnectTransport` / `reconnectDevice` | `RelayMuxSessionManager`/`Registry` | 切换在 CompositeTransport 内 |
| `createMuxSession` 里的二选一逻辑 | `RelayMuxSessionManager:65` | 改为 `new CompositeTransport(...)` |

## P2P 协商的处理

采用**组合**而非合并：`P2PConnectionManager` 类保留（逻辑成熟，含 PeerConnection/DataChannel/ICE/信令），但：
- 不再由 `MainActivity` 创建，改由 `CompositeTransport` 持有（per-device）
- `P2PConnectionManager.Listener` 由 `CompositeTransport` 实现：`onConnected` → 设 pendingLeg；`onDisconnected` → 回退；`onError` → 标记不可用
- P2P 从"全局单例"变为"per-device"（随 CompositeTransport 创建/释放）
- **审查修正**：构造改为接收 `WebTermApi` + `PeerConnectionFactory`（注入而非自建）

## 常驻（已做，沿用）

监控通道持有 `muxSession`，`releaseIfIdle` 不停。`CompositeTransport` 不影响这套——只要通道在，muxSession 不释放，CompositeTransport（含两条 leg）常驻。直连、relay 都覆盖。

## 命名清理（收尾）

- `RelayMuxSessionManager` → `MuxSessionManager`（不限于 relay）
- 删 `HomeServerCoordinator.resume()` 死代码（上一轮遗留）

## 实施分期

### 阶段 1：准备工作（不改生产路径）
- `AppContainer` 创建全局 `PeerConnectionFactory`
- `WebTermApplication` 增加静态 `getApplicationContext()`
- `P2PConnectionManager` 构造改为接收 `WebTermApi` + `PeerConnectionFactory`（兼容新老构造）
- `CompositeTransport implements MuxTransport`，只挂 WS leg，跑通 `start/close/isConnected/send` + 转发 listener
- **验证**：现有功能完全不受影响（CompositeTransport 未替换生产路径）

### 阶段 2：替换 + P2P 接入
- `createMuxSession` 改为 `new CompositeTransport(...)`
- `CompositeTransport` 持有 `P2PConnectionManager`，relay 设备创建时自动启动协商
- 实现 P2P leg onOpen → pendingLeg + listener.onOpen → 等待确认 → activeLeg 切换
- 删除视图层所有 P2P 触发（`startP2PIfRelayDevice`、`disconnect`、`Listener`、`setTransportProvider`）
- **验证**：relay 设备 P2P 自动协商、自动切换；进/出终端、切后台不触发 P2P 重启

### 阶段 3：回退 + 清理
- P2P 断 → pendingLeg=WS → listener.onOpen → 等待确认 → activeLeg 回退
- 两条都断 → listener.onClosed → MuxSession scheduleReconnect → CompositeTransport.start() 重来
- 删除 `reconnectTransport`/`reconnectDevice`/`transportProvider`
- **验证**：断 P2P 自动回退 WS，指示灯不闪，数据续传不丢；P2P 恢复自动切回

### 阶段 4：收尾
- 重命名 `RelayMuxSessionManager` → `MuxSessionManager`
- 删 `resume()` 死代码
- **验证**：全量回归

阶段 1→2→3 有依赖，顺序做；阶段 4 收尾。每阶段结束都可编译运行。

## 风险与边界

1. **pendingLeg 切换竞态**（审查修正）：先在新 leg 上确认通道全就绪，再停旧 leg。避免双 leg 并存导致服务端通道冲突。
2. **切换瞬间数据中断**：换 leg + 重新 ws-connect 有几十 ms 中断，靠 `lastSeq` 续传不丢。不做双轨零中断（复杂度太高）。
3. **P2P 协商失败**：CompositeTransport 检测到失败/超时，标记 P2P 不可用，WS 持续承载。可周期性重试 P2P。
4. **PeerConnectionFactory 全局单例**（审查修正）：提升到 AppContainer，避免 per-device 重复初始化。
5. **P2PConnectionManager 注入 WebTermApi**（审查修正）：共享连接池，不重复创建。
6. **Context 获取**（审查简化）：`WebTermApplication.getApplicationContext()` 静态方法，不经过 4 层传参。
7. **直连回归**：直连无 P2P，CompositeTransport 退化为 WS only，行为须与现在完全一致。
8. **后台保活**：沿用当前"3 分钟后 detach"策略（上一轮已定，不再改）。
9. **阶段 1 不改生产路径**（审查修正）：CompositeTransport 在阶段 1 只写不替换，阶段 2 再切，确保主干不中断。

## 验证方法

- **logcat**（`MuxSession`/`CompositeTransport`/`P2PConnectionManager`）：
  - `mux open` 全程只在首次进会话列表出现一次；进/出终端、切后台无新 `mux open`
  - P2P 协商/切换有独立日志，与视图动作解耦
- **进终端**：指示灯立即绿（WS 已连）；P2P 协商好无感切换（不闪、数据不断）
- **P2P 断线**：自动回退 WS，指示灯不闪，`lastSeq` 续传不丢
- **P2P 恢复**：自动切回 P2P，无感
- **直连**：无 P2P 日志，纯 WS 常驻，行为不变
- **切后台/回前台**：WS 保持（3 分钟内），无重连

---

## 与 v1 的差异总结

| 项 | v1 | v2（审查修正） |
|----|----|-----------------|
| 切换 leg 时序 | 直接 activeLeg=P2P+onOpen，双 leg 并存 | pendingLeg 过渡，确认后再切，停旧 leg |
| PeerConnectionFactory | per-device，可能重复创建 | AppContainer 全局单例 |
| WebTermApi | P2PConnectionManager 自己 new | 注入共享 |
| Context | AppContainer→Registry→Manager→Composite 4 层 | WebTermApplication 静态方法 |
| 阶段 1 风险 | 替换生产路径，P2P 暂时失效 | 不改生产路径，阶段 2 再切 |
