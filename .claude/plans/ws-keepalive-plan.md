# WS 常驻:消除进出终端的连接重连卡顿

## 问题根因

一个 device 一条 WS(mux 复用多个通道)。首页 `ServerSessionMonitor` 开着一条监控通道占住 WS,终端本该只是再开一个通道。但当前代码"先关旧通道再开新通道",中间 WS 无人持有就被 `releaseIfIdle` 关掉,导致每次进出终端都重建 WS(TCP+TLS+握手,2~3s)。

具体两条断点:
1. **进终端**:`MainActivity.showTerminal`(:504)第一行 `mHomeCoordinator.pause()` → `stopAllGroups()` → `ServerSessionMonitor.stop()` → `closeChannel` + `releaseIfIdle`(此时终端通道还没开,WS 断)。随后终端 `forDevice` 命中不到,重建 WS。
2. **回首页/切后台**:`TerminalConnection.close()` / `pauseCurrentConnection` → `releaseIfIdle`,若监控通道已停,WS 断。

## 设计目标

**WS 生命周期跟随"当前选中的 server",而非页面可见性。** 只要用户还在跟这个 server 打交道(在它的会话列表、或在它的某个终端里、或 app 在后台),这条 WS 就常驻;只有真正离开这个 server(回设备列表、切到另一个 server)才断。

## 设计决策

- **后台行为**:切后台保持 WS(onPause 不断)。回前台秒恢复,无重连卡顿。WS 自带 15s ping 保活;server 端若 idle 断开,`MuxSession.scheduleReconnect` 自动兜底。
- **P2P 切换**:本次不动。relay 设备 P2P 协商完成后 `reconnectDevice` 仍会切一次 transport,但这是单次、且发生在终端内容已显示之后,影响远小于"进出终端每次都重连"。列为后续优化。
- **WS 复用的前提**:终端通道与监控通道在同一个 `RelayMuxSessionManager`(同 baseUrl+cookie+deviceId),本就是同一条 WS 的两个通道,可共存。

## 改动清单

### 1. `HomeServerCoordinator.java` — 解耦"UI 暂停"与"断 WS"

- `pause()`:移除 `stopAllGroups()` 调用,只保留 `sessionLoadGeneration++` 和 `refreshScheduler.cancel()`。含义变为"暂停 UI 刷新,WS 保持"。
- 新增 `detach()`:`stopAllGroups()` + `activeGroups.clear()`。真正停监控通道、释放 WS。用于回设备列表。
- `resume()`:改为幂等——若 `activeGroups` 里的 holder 的 monitor 仍在运行(已 start 且未 stop),不重复 `connectManagerWS`/`start`,只重置刷新调度;否则按原逻辑 `connectManagerWS`。
- `loadDeviceSessions`(:92)开头的 `stopAllGroups()` 保留(切换 server 时停旧 monitor,合理)。
- `destroy()`:仍调 `pause()` + 清理(实际退 app)。

### 2. `ServerSessionMonitor.java` — `start()` 幂等

当前 `start()` 无条件 `openChannel`。常驻后 `resume()` 可能再次进入 `start()`,会重复 `sendWsConnect` 导致服务端通道重复。需加幂等:
- 增加 `boolean channelOpened` 标记。`start()` 中若 `enabled && channelOpened && relayMuxSession != null`,直接 return(或仅 `relayMuxSession.start()` 复用)。
- `stop()` 中重置 `channelOpened = false`。
- 仅在 `muxSession` 物理重连后(`onMuxDisconnected` → 重连成功)才需要重新 `openChannel`——这部分由 `MuxSession.onMuxConnected` → `reopenChannels` 已处理(`RelayMuxSessionManager` 层),monitor 层无需重复发。

### 3. `MainActivity.java` — 在正确的导航点断 WS

- `showSessionHome`(:232,回设备列表):`mHomeCoordinator.pause()` → 改为 `mHomeCoordinator.detach()`。这是"离开 server",该断 WS。
- `showTerminal`(:504,进终端):`mHomeCoordinator.pause()` 保持(不断 WS)。终端 `connectRelayMux` 的 `forDevice` 命中已有 manager,WS 已 connected,直接 `openTerminalChannel` → `sendWsConnect`,秒开。
- `showDeviceSessions`(:291,进/回会话列表):不变。`loadDeviceSessions` 内部 `stopAllGroups`+新建 holder,会重建 monitor——这里需确认:从终端 back 到会话列表时,WS 会不会因 stopAllGroups 断?
  - 此时终端已被 `closeTerminal(false)`(:293)关掉终端通道,若监控通道也被 `stopAllGroups` 关,WS 确实会断一次,然后新 holder 再开。
  - **优化**:从终端 back 到同 server 的会话列表,复用已有 holder(若 `mSelectedServer` 未变且 `activeGroups` 非空),不 `stopAllGroups` 重建,只 `attachSessionAdapter` + `resume`。这样 WS 不断。需在 `showDeviceSessions` 入口判断"server 未变且 holder 存活"走轻量路径。
- `onPause`(:159):终端页 `pauseCurrentConnection`(关终端通道,WS 因监控通道在而不断);DEVICE_SESSIONS 页 `mHomeCoordinator.pause()`(不断 WS)。✓ 符合"后台保持"。
- `onResume`(:136):终端页若 `DISCONNECTED` → `connectTerminal`(WS 还在,只重开终端通道,秒连);DEVICE_SESSIONS 页 `mHomeCoordinator.resume()`(monitor 在,不重连)。✓

### 4. `TerminalConnection.java` — 无需改动

`close()` 的 `releaseIfIdle` 逻辑不变。因为监控通道仍占着,`RelayMuxSessionManager.channels` 非空,`stopIfIdle` 里 `if (!channels.isEmpty()) return;` 直接返回,WS 不会停。这是复用能成立的关键,无需改代码,只需保证监控通道不被提前关。

### 5. `RelayMuxSessionRegistry.java` — 无需改动

`releaseIfIdle` 的引用计数语义正确:只要还有通道(监控或终端),就不释放。常驻靠"监控通道常驻"实现,registry 层无需改。

## 时序对比(改后)

```
进会话列表:开监控通道 → 建 WS(首次握手 2~3s,不可避免)
进终端:    监控通道保持 → 终端 forDevice 命中 → sendWsConnect(几十 ms)→ 秒开
回会话列表:关终端通道,监控通道在 → WS 不断
回设备列表:detach → 关监控 → WS 断(合理,不再用此 server)
切后台:    WS 保持
回前台:    WS 还在 → 无重连
```

## 风险与边界

1. **monitor 在终端页仍收会话更新**:`sessionAdapter` 还在(只是不显示),数据更新无害,回会话列表时数据新鲜。流量极小(只有标题/状态变更)。
2. **切 server**:`loadDeviceSessions` 停旧开新,WS 正确切换。多 server 各自独立 registry 项,互不影响。
3. **monitor.start 幂等**:必须正确处理,否则 `resume` 重复 `openChannel` → 服务端通道重复。是本方案最易出 bug 的点,需重点测。
4. **从终端 back 到会话列表的轻量路径**:需准确判断"同 server 且 holder 存活",否则误判会泄漏旧 holder 或断 WS。
5. **后台保活耗电**:15s ping,数据量小。可接受。若后续反馈耗电,加"后台 N 分钟后 detach"。
6. **WS 断线**:常驻期间若网络抖动,`MuxSession` 自动重连,重连后 `reopenChannels` 重新开监控+终端通道,`TerminalConnection` 走 RECONNECTING → CONNECTED,`lastSeq` 续传,无内容丢失。

## 验证方法

1. **logcat**:`adb logcat -s RelayMuxSessionManager MuxSession WebSocketMuxTransport TerminalConnection`。进/出终端、切后台回前台,不应出现新的 `mux open`/`using relay websocket transport`(只有首次进会话列表出现一次)。`mux open` 只出现一次 = WS 常驻成功。
2. **指示灯**:进终端应几乎立即变绿(WS 已 connected,只等 `ws-connected` 一跳)。
3. **切后台回前台**:指示灯不闪,内容无重载。
4. **切 server**:旧 server 的 WS 断开(logcat 出现 `closing mux`),新 server 建立。
5. **会话标题同步**:在终端页时,另一端改了会话标题,会话列表数据应更新(回列表可见)。
