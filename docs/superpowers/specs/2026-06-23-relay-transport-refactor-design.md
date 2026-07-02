# Go Core Relay 层 virtualSocket / transport 重构设计

日期: 2026-06-23

## 背景

当前 `go-core/internal/relay/` 中的 `virtualSocket` 和 `connectionTransport` 存在以下问题：

1. **循环引用**：`connectionTransport` 持有 `map[string]*virtualSocket`，`virtualSocket` 持有 `relayTransport` 接口引用（由 `connectionTransport` 实现），形成 `transport → sockets → transport` 的循环。
2. **并发写不安全**：`sendJSON` 和 `sendBinary` 直接写 `*websocket.Conn`，多个 virtualSocket goroutine 可能同时写，违反 `nhooyr.io/websocket` 的并发限制。
3. **关闭路径不统一**：`Close()` 和 `CloseWithNotify()` 两个方法行为不同，调用方需要知道何时用哪个。
4. **virtualSocket 承担了不属于它的职责**：关闭时通知 relay、从 transport 注册表注销自己。

## 目标

1. **职责分离**：virtualSocket 只负责实现 `session.Socket` 接口；transport 负责注册表管理、帧路由、relay 通知。
2. **被动清理**：transport 通过 `Emit()` 返回值感知 socket 关闭，不依赖回调。
3. **单向依赖**：virtualSocket 不持有 transport 类型引用，只持有一个 `writeFn` 闭包用于写数据。
4. **线程安全**：所有对 `*websocket.Conn` 的写操作串行化。

## 最终设计

### 架构

```
connectionTransport (唯一持有 conn 和 sockets map)
  │
  ├── writeMu sync.Mutex           ← 串行化所有 WS 写
  ├── mu sync.RWMutex              ← 保护 sockets map
  ├── sockets map[string]*virtualSocket
  │
  ├── newSocket(id, protocol)
  │     └─► 创建 virtualSocket，注入 writeFn 闭包
  │         注册到 sockets map
  │
  ├── handleBinaryFrame(data)
  │     └─► 解码隧道帧 → socket.Emit(payload)
  │         Emit 返回 false → removeSocket(id)
  │
  ├── closeSocket(id)
  │     └─► delete from map + socket.Close() + notify relay
  │
  ├── closeAll()
  │     └─► 清空 map + 所有 socket.Close()
  │         （不通知 relay，连接已断）
  │
  └── removeSocket(id)
        └─► delete from map + notify relay (ws-close)

virtualSocket (不持有 transport 引用)
  │
  ├── id, protocol
  ├── incoming chan              ← 接收来自 transport 的数据
  ├── done chan                  ← 关闭信号
  ├── writeFn func(MessageType, []byte) error  ← 闭包
  │
  ├── Read()  → 从 incoming channel 读
  ├── Write() → 调用 writeFn(messageType, data)
  ├── Close() → closeOnce: close(done)，不做任何通知
  └── Emit()  → 写入 incoming channel，返回 false 表示已关闭/溢出
```

### virtualSocket

```go
type virtualSocket struct {
    id       string
    protocol string
    incoming chan virtualMessage
    done     chan struct{}
    closeOnce sync.Once
    writeFn func(session.MessageType, []byte) error
}
```

- `writeFn` 是唯一的闭包依赖，由 transport 在 `newSocket` 时注入。virtualSocket 不知道数据如何编码、发到哪里。
- `Close()` 只做 `close(done)`，不触发任何外部回调。
- `Emit()` 返回 `bool`——transport 通过返回值被动感知 socket 是否存活。

### connectionTransport

```go
type connectionTransport struct {
    conn    *websocket.Conn
    writeMu sync.Mutex
    mu      sync.RWMutex
    sockets map[string]*virtualSocket
}
```

- `newSocket(id, protocol)` 创建 virtualSocket 并注入 `writeFn` 闭包。
- `handleBinaryFrame(data)` 解码隧道帧 → `socket.Emit(payload)`。返回 false 时调 `removeSocket(id)`。
- `closeSocket(id)` 主动关闭：从 map 删除 + `socket.Close()` + 通知 relay。
- `closeAll()` 批量关闭：清空 map + 所有 `socket.Close()`，不通知 relay（连接已断）。
- `removeSocket(id)` 从 map 删除 + 通知 relay（`ws-close` 消息）。
- `sendJSON` 和 `writeWS` 通过 `writeMu` 串行化。

### 关闭路径

| 触发源 | 路径 | relay 被通知 |
|--------|------|:---:|
| relay 发 ws-close | `closeSocket(id)` → delete + Close + notify | ✅ |
| PTY 进程退出 | `client.Close()` → `socket.Close()` → close(done)。下次 relay 发数据 → Emit 返回 false → `removeSocket(id)` | ✅（延迟） |
| agent WS 断开 | `closeAll()` → 清空 map + 所有 Close() | ❌（连接已断，无需通知） |
| incoming 溢出 | Emit 返回 false → `removeSocket(id)` | ✅ |
| handleWSConnect 失败 | `closeSocket(id)` | ✅ |

### writeFn 闭包

```go
socket := newVirtualSocket(id, protocolName, func(mt session.MessageType, data []byte) error {
    extra := protocol.WSDataText
    if mt == session.MessageBinary {
        extra = protocol.WSDataBinary
    }
    frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, extra, data)
    if err != nil {
        return err
    }
    return t.writeWS(frame)
})
```

这是 virtualSocket 和 transport 之间唯一的耦合点。virtualSocket 不 import relay 包的任何类型，不知道 `connectionTransport` 的存在。

### 不变的部分

- 隧道帧格式不变（兼容 relay server）
- `session.Socket` 接口不变
- `session.Client` 和 `session.ManagerClient` 不变
- 对外的 relay client 行为不变

## 设计决策记录

### 为什么不用 `Closed() <-chan struct{}` 方案？

transport 为每个 socket 启动一个 goroutine 监听 `Closed()` channel 会增加 goroutine 数量（虽然实际 session 数很少，但不必要）。`Emit` 返回值方案利用了已有的数据路径，零额外开销。

### 为什么 PTY 退出时 relay 通知是延迟的？

virtualSocket 关闭时不主动通知，transport 在下一次收到该 socket 的数据时通过 Emit 返回 false 触发清理。如果 relay 不再发数据，socket 残留为一个 map entry，内存开销可忽略，在 agent 断开时随 `closeAll()` 批量清理。

### 为什么 `closeAll()` 不通知 relay？

agent WS 已断开，通知消息无法发出。relay 端在 `ws.on('close')` 中已有 `cleanupAgentState` 逻辑，会自动清理所有相关隧道。
