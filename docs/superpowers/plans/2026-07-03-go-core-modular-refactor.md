# Go Core 模块化架构重构计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 go-core 从平面化、紧耦合的包结构重构为分层清晰、职责单一的模块化架构，按风险从低到高分三个阶段执行。

**Architecture:** 采用分层架构 + 端口-适配器模式。核心思路：(1) 抽取共享的 session 路由和 CRUD 逻辑消除重复；(2) 拆分上帝对象 `app.App` 为独立服务接口；(3) 将 `V2Client` 拆分为连接管理/流多路复用/HTTP代理/P2P 四个独立组件；(4) 清理 `Socket` 接口移除应用层概念；(5) 拆分 `session` 包为 PTY 进程/终端模拟/事件缓冲三个独立模块。

**Tech Stack:** Go 1.25.1, nhooyr.io/websocket, github.com/creack/pty, github.com/pion/webrtc/v4

## Global Constraints

- Go 1.25.1，模块路径 `webterm/go-core`
- 所有生产代码在 `go-core/internal/` 下，遵循 Go 内部包约定
- 保持向后兼容：所有现有测试必须在每个任务完成后通过
- 每个任务以独立可测试的增量交付，commit 粒度为一个任务一个 commit
- 使用 `go test ./...` 验证每次变更

---

### Task 1: 抽取共享 Session 路由逻辑 — 消除 OpenSessionOrManager 重复

**背景：** `mux/handler.go` 的 `OpenSessionOrManager` 和 `relay/client_v2.go` 的 `openSessionOrManager` 包含几乎相同的 session 路径分发逻辑。`relay/client_v2.go` 的 `routeMemoryAPI` 和 `direct/server.go` 的 session CRUD 也重复。

**策略：** 创建新文件 `internal/application/session_router.go`，将 session 路径分发和 CRUD 逻辑集中到一处。

**Files:**
- Create: `go-core/internal/application/session_router.go`
- Modify: `go-core/internal/mux/handler.go`
- Modify: `go-core/internal/relay/client_v2.go`
- Modify: `go-core/internal/direct/server.go`

**Interfaces:**
- Produces: `SessionRouter` 结构体，方法 `RouteOpen(path, protocols) (func(), error)` 和 `RouteHTTP(method, path, body) (int, []byte, error)`
- Consumes: `*session.Manager`（已有）

- [ ] **Step 1: 创建 `internal/application/session_router.go`**

```go
package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// SessionRouter 统一 session 路径分发和 CRUD 逻辑，
// 供 direct server 和 relay agent 共用，消除重复。
type SessionRouter struct {
	manager *session.Manager
}

func NewSessionRouter(manager *session.Manager) *SessionRouter {
	return &SessionRouter{manager: manager}
}

// RouteOpen 根据 WebSocket 路径和子协议创建 ManagerClient 或终端 Client。
// 返回 start 函数由调用方在握手 ack 完成后调用。
func (r *SessionRouter) RouteOpen(
	ctx context.Context,
	socket session.Socket,
	path string,
	protocols []string,
) (func(), error) {
	clean := cleanPath(path)
	switch {
	case clean == "/ws/sessions":
		mc := session.NewManagerClient(socket)
		return func() { go mc.Run(ctx, r.manager) }, nil

	case strings.HasPrefix(clean, "/ws/sessions/"):
		id := strings.TrimPrefix(clean, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := r.manager.Get(id)
		if !ok {
			return nil, fmt.Errorf("session %s not found", id)
		}
		mode := session.ClientModeFromProtocol(selectProtocol(protocols))
		client := session.NewClient(socket, terminal, mode)
		return func() { go client.Run(ctx) }, nil

	default:
		return nil, fmt.Errorf("unknown path: %s", path)
	}
}

// RouteHTTP 处理 session CRUD 的 HTTP 请求代理（供 relay agent 使用）。
func (r *SessionRouter) RouteHTTP(method string, rawPath string, body []byte) (int, []byte, error) {
	path := cleanPath(rawPath)

	if method == http.MethodGet && path == "/api/sessions" {
		return marshalStatus(http.StatusOK, r.manager.List())
	}
	if method == http.MethodPost && path == "/api/sessions" {
		var req struct {
			Name string `json:"name"`
			CWD  string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &req)
		}
		terminal, err := r.manager.Create(req.Name, req.CWD)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		return marshalStatus(http.StatusCreated, terminal.Info())
	}
	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		id, _ = url.PathUnescape(id)
		if method == http.MethodPatch {
			var req struct {
				Name string `json:"name"`
			}
			if len(body) > 0 {
				_ = json.Unmarshal(body, &req)
			}
			terminal, ok := r.manager.Rename(id, req.Name)
			if !ok {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return marshalStatus(http.StatusOK, terminal.Info())
		}
		if method == http.MethodDelete {
			if !r.manager.Close(id) {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return http.StatusNoContent, []byte{}, nil
		}
	}
	return http.StatusNotFound, nil, errors.New("not found")
}

func (r *SessionRouter) Manager() *session.Manager {
	return r.manager
}

// --- helpers (从 mux/session.go 和 relay/client_v2.go 提取) ---

func selectProtocol(protocols []string) string {
	for _, p := range protocols {
		if p == protocol.ScreenSubprotocol {
			return protocol.ScreenSubprotocol
		}
	}
	for _, p := range protocols {
		if p == protocol.BinarySubprotocol {
			return protocol.BinarySubprotocol
		}
	}
	for _, p := range protocols {
		if p == protocol.JSONSubprotocol {
			return protocol.JSONSubprotocol
		}
	}
	return protocol.JSONSubprotocol
}

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
}

func marshalStatus(status int, value any) (int, []byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return http.StatusInternalServerError, nil, err
	}
	return status, payload, nil
}
```

- [ ] **Step 2: 重构 `mux/handler.go` — 委托给 SessionRouter**

修改 `mux/handler.go` 中的 `OpenSessionOrManager`，改为接收 `*application.SessionRouter`：

```go
package mux

import (
	"context"

	"webterm/go-core/internal/application"
)

// OpenSessionOrManager 是 direct server 和 relay agent 共用的 OnOpen 处理器。
// 委托给 application.SessionRouter 统一处理路径分发。
// 返回的 start 在 ws-connected 写出成功后由 mux 调用，保证握手 ack 先于通道数据。
func OpenSessionOrManager(
	ctx context.Context,
	router *application.SessionRouter,
	vs *VirtualSocket,
	path string,
	protocols []string,
) (func(), error) {
	return router.RouteOpen(ctx, vs, path, protocols)
}
```

- [ ] **Step 3: 更新 `direct/server.go` — 传入 SessionRouter**

修改 `direct/server.go`，创建 `SessionRouter` 并传入 `mux.OpenSessionOrManager`：

在 `routeWebSocket` 方法中（约第127行），将：
```go
sess := mux.Serve(session.NewWebSocketAdapter(conn), &mux.ServeOpts{
    OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) (func(), error) {
        return mux.OpenSessionOrManager(ctx, direct.app.Sessions(), vs, p, protos)
    },
})
```
改为：
```go
router := application.NewSessionRouter(direct.app.Sessions())
sess := mux.Serve(session.NewWebSocketAdapter(conn), &mux.ServeOpts{
    OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) (func(), error) {
        return mux.OpenSessionOrManager(ctx, router, vs, p, protos)
    },
})
```

并在文件顶部添加 import：
```go
import (
    // ... 已有 imports ...
    "webterm/go-core/internal/application"
)
```

- [ ] **Step 4: 重构 `relay/client_v2.go` — 删除重复的 `openSessionOrManager` 和 `routeMemoryAPI`**

删除 `relay/client_v2.go` 中的 `openSessionOrManager` 方法（约第255-288行）和 `routeMemoryAPI` 方法（约第290-336行）。

在 `V2Client` 结构体中添加 `router` 字段：
```go
type V2Client struct {
	cfg    config.RelayConfig
	app    *app.App
	router *application.SessionRouter   // 新增

	writeMu sync.Mutex
	mu      sync.Mutex
	http    map[string]chan relaycore.Frame
	ws      map[string]*relayStreamSocket
	p2p     map[string]*p2pPeer
}
```

修改 `NewV2`：
```go
func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	return &V2Client{
		cfg:    cfg,
		app:    application,
		router: application.NewSessionRouter(application.Sessions()),  // 新增
		http:   make(map[string]chan relaycore.Frame),
		ws:     make(map[string]*relayStreamSocket),
		p2p:    make(map[string]*p2pPeer),
	}
}
```

修改 `handleStreamOpen` 方法（约第235行），将：
```go
start, err := client.openSessionOrManager(ctx, socket, route.Path, route.Subprotocol)
```
改为：
```go
start, err := client.router.RouteOpen(ctx, socket, route.Path, []string{route.Subprotocol})
```

修改 `respondHTTP` 方法（约第194行），将：
```go
status, payload, err := client.routeMemoryAPI(meta.Method, path, body)
```
改为：
```go
status, payload, err := client.router.RouteHTTP(meta.Method, path, body)
```

并在文件顶部添加 import：
```go
import (
    // ... 已有 imports ...
    "webterm/go-core/internal/application"
)
```

同时删除不再需要的 `"net/url"` 和 `"strings"` import（如果没有其他地方使用的话——检查 `respondHTTP` 中的 `path` 拼接逻辑是否还在用这些包）。

- [ ] **Step 5: 更新 `relay/client_v2_p2p.go` 中的 P2P DataChannel OnOpen 回调**

修改 `relay/client_v2_p2p.go` 中 `acceptP2POffer` 方法里的 mux OnOpen 回调（约第89-91行），将：
```go
OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) (func(), error) {
    return mux.OpenSessionOrManager(ctx, client.app.Sessions(), vs, path, protocols)
},
```
改为：
```go
OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) (func(), error) {
    return mux.OpenSessionOrManager(ctx, client.router, vs, path, protocols)
},
```

- [ ] **Step 6: 运行测试验证**

```bash
cd go-core && go test ./internal/application/... ./internal/mux/... ./internal/relay/... ./internal/direct/...
```
期望：全部通过。

- [ ] **Step 7: 运行 smoke tests 验证端到端**

```bash
cd go-core && go build ./cmd/webterm-agent && go build ./cmd/webterm-relay
go test ./cmd/webterm-flow-smoke/... ./cmd/webterm-relay-flow-smoke/...
```
期望：编译通过，smoke test 通过。

- [ ] **Step 8: Commit**

```bash
git add go-core/internal/application/session_router.go go-core/internal/mux/handler.go go-core/internal/relay/client_v2.go go-core/internal/relay/client_v2_p2p.go go-core/internal/direct/server.go
git commit -m "refactor: extract shared SessionRouter to eliminate route duplication

Move session path routing and CRUD logic from mux/handler.go,
relay/client_v2.go, and direct/server.go into a single
application.SessionRouter. This eliminates the duplicate
OpenSessionOrManager and routeMemoryAPI implementations.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 清理 Socket 接口 — 移除 Subprotocol() 方法

**背景：** `session.Socket` 接口的 `Subprotocol()` 方法是应用层概念（协议协商），不应该绑定在传输层抽象上。所有 Socket 实现（WebSocketAdapter、VirtualSocket、relayStreamSocket、p2pDataChannelSocket）都需要携带 subprotocol 信息。

**策略：** 从 `Socket` 接口移除 `Subprotocol()`，改为在需要协议信息的地方通过独立参数传入。

**Files:**
- Modify: `go-core/internal/session/socket.go`
- Modify: `go-core/internal/mux/virtual_socket.go`
- Modify: `go-core/internal/mux/session.go`
- Modify: `go-core/internal/relay/client_v2.go`
- Modify: `go-core/internal/relay/client_v2_p2p.go`

**Interfaces:**
- Modifies: `session.Socket` — 移除 `Subprotocol() string`
- Consumes: 所有 Socket 实现者需要移除 `Subprotocol()` 方法

- [ ] **Step 1: 从 `session/socket.go` 移除 `Subprotocol()` 方法**

修改 `Socket` 接口：
```go
type Socket interface {
	Read(context.Context) (MessageType, []byte, error)
	Write(context.Context, MessageType, []byte) error
	Close() error
}
```

从 `WebSocketAdapter` 移除 `Subprotocol()` 方法（删除约第53-55行）。

- [ ] **Step 2: 从 `mux/virtual_socket.go` 移除 `Subprotocol()`**

删除 `VirtualSocket.Subprotocol()` 方法（约第84-86行）。`VirtualSocket` 的 `protocol` 字段保留，但不再通过接口暴露——mux 内部仍然需要它。

- [ ] **Step 3: 从 `relay/client_v2.go` 的 `relayStreamSocket` 移除 `Subprotocol()`**

删除 `relayStreamSocket.Subprotocol()` 方法（约第451-453行）。

在 `handleStreamOpen` 中，`relayStreamSocket` 创建时仍然接收 `route.Subprotocol`，但不再通过 Socket 接口暴露。修改 `newRelayStreamSocket` 签名保持 `protocolName` 参数不变，只是删除 `Subprotocol()` 方法。

- [ ] **Step 4: 从 `relay/client_v2_p2p.go` 的 `p2pDataChannelSocket` 移除 `Subprotocol()`**

删除 `p2pDataChannelSocket.Subprotocol()` 方法（约第282-284行）。

- [ ] **Step 5: 运行测试验证**

```bash
cd go-core && go test ./internal/session/... ./internal/mux/... ./internal/relay/... ./internal/direct/...
```
期望：全部通过（`Subprotocol()` 在 session 包的测试中不应被调用——如果有，需要调整测试）。

- [ ] **Step 6: Commit**

```bash
git add go-core/internal/session/socket.go go-core/internal/mux/virtual_socket.go go-core/internal/relay/client_v2.go go-core/internal/relay/client_v2_p2p.go
git commit -m "refactor: remove Subprotocol() from Socket interface

Socket is a transport-layer abstraction; protocol negotiation
belongs to the application layer. Removed Subprotocol() from
the interface and all implementations. Protocol information is
now passed as a separate parameter where needed.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 拆分 app.App — 分离日志和配置服务

**背景：** `app.App` 是上帝对象，持有 config、sessions、logs、runtime 状态。本任务只做最安全的部分：将 `logs.Logger` 从 `App` 中分离为独立服务，`App` 通过组合使用。

**策略：** 不改动 `App` 的公开 API——只将内部实现委托给独立组件。这是最小风险的改进。

**Files:**
- Modify: `go-core/internal/app/app.go`

**Interfaces:**
- Produces: `App` 内部将日志相关调用委托给 `*logs.Logger`，API 不变

- [ ] **Step 1: 在 `app.go` 中显式声明 Logger 为公开字段**

当前 `App.logs` 是私有字段，通过 `Logs()` 和 `Log()` 方法访问。保持这些方法不变，但确保 `App` 不直接操作 `logs` 的内部状态，只通过 `logs.Logger` 的公开方法。

**无需代码修改**——当前实现已经符合要求。本步骤仅确认现状合理。

- [ ] **Step 2: 引入 `ConfigService` 概念 — 提取配置变更通知**

在 `app.go` 中添加 `onConfigChange` 回调机制，解耦配置更新和副作用：

```go
type App struct {
	cfg             config.Config
	version         string
	sessions        *session.Manager
	logger          *logs.Logger          // 重命名 logs -> logger，更清晰
	mu              sync.RWMutex
	runtimeMode     string
	restartRequired bool
	direct          DirectStatus
	relay           RelayStatus
	onConfigChange  []func(config.Config) // 新增：配置变更回调
}
```

修改 `New` 函数中 `logs` → `logger`：
```go
application := &App{
    cfg:         cfg,
    version:     version,
    logger:      logs.New(logs.DefaultCapacity),
    // ...
}
```

修改 `Logs()` 方法返回 `logger`：
```go
func (app *App) Logs() *logs.Logger {
    return app.logger
}
```

修改 `Log()` 方法：
```go
func (app *App) Log(level string, source string, message string) {
    if app.logger != nil {
        app.logger.Add(level, source, message)
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd go-core && go test ./internal/app/... ./internal/control/... ./internal/runtime/...
```
期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add go-core/internal/app/app.go
git commit -m "refactor: rename App.logs to App.logger, add onConfigChange hooks

Internal cleanup only - no API changes. Renames the private field
for clarity and adds infrastructure for config change listeners
to be used in future refactoring steps.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 拆分 V2Client — 分离 HTTP 代理为独立组件

**背景：** `V2Client` 的 `handleHTTPStream`、`respondHTTP`、`deliverHTTP` 方法实现了完整的 HTTP 请求代理（session CRUD）。本任务将这些提取为独立的 `HTTPProxy` 组件。

**Files:**
- Create: `go-core/internal/relay/http_proxy.go`
- Modify: `go-core/internal/relay/client_v2.go`

**Interfaces:**
- Produces: `HTTPProxy` 结构体，方法 `Handle(ctx, conn, frame)` 和 `Deliver(frame)`
- Consumes: `*application.SessionRouter`（来自 Task 1）

- [ ] **Step 1: 创建 `relay/http_proxy.go`**

```go
package relay

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/relaycore"
)

// HTTPProxy 处理 relay 侧 HTTP 请求代理。接收 relay 发来的 HTTPHeaders/HTTPChunk
// 帧，调用 SessionRouter 执行 session CRUD，将结果写回 relay。
type HTTPProxy struct {
	router  *application.SessionRouter
	writeMu *sync.Mutex    // 与 V2Client 共享写锁
	mu      sync.Mutex
	streams map[string]chan relaycore.Frame
}

func NewHTTPProxy(router *application.SessionRouter, writeMu *sync.Mutex) *HTTPProxy {
	return &HTTPProxy{
		router:  router,
		writeMu: writeMu,
		streams: make(map[string]chan relaycore.Frame),
	}
}

// HandleHTTPHeaders 处理 HTTPHeaders 帧——创建新 stream 并启动处理。
func (p *HTTPProxy) HandleHTTPHeaders(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	ch := make(chan relaycore.Frame, 8)
	p.mu.Lock()
	p.streams[frame.StreamID] = ch
	p.mu.Unlock()
	go p.processStream(ctx, conn, frame, ch)
}

// DeliverChunk 将 HTTPChunk 帧投递到对应 stream。
func (p *HTTPProxy) DeliverChunk(frame relaycore.Frame) {
	p.mu.Lock()
	ch := p.streams[frame.StreamID]
	p.mu.Unlock()
	if ch == nil {
		return
	}
	select {
	case ch <- frame:
	default:
		close(ch)
		p.mu.Lock()
		delete(p.streams, frame.StreamID)
		p.mu.Unlock()
	}
}

func (p *HTTPProxy) processStream(ctx context.Context, conn *websocket.Conn, first relaycore.Frame, ch <-chan relaycore.Frame) {
	defer func() {
		p.mu.Lock()
		delete(p.streams, first.StreamID)
		p.mu.Unlock()
	}()

	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(first.Payload, &meta); err != nil {
		p.writeErrorFrame(ctx, conn, first.StreamID, []byte("invalid http metadata"))
		return
	}
	body := make([]byte, 0)
	for {
		select {
		case <-ctx.Done():
			return
		case frame, ok := <-ch:
			if !ok {
				return
			}
			if frame.Type != relaycore.FrameTypeHTTPChunk {
				continue
			}
			body = append(body, frame.Payload...)
			if frame.Flags.Has(relaycore.FrameFlagFin) {
				p.respond(ctx, conn, first.StreamID, meta, body)
				return
			}
		}
	}
}

func (p *HTTPProxy) respond(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}
	status, payload, err := p.router.RouteHTTP(meta.Method, path, body)
	if err != nil && status == 0 {
		status = http.StatusInternalServerError
	}
	if err != nil {
		p.writeErrorFrame(ctx, conn, streamID, []byte(err.Error()))
		return
	}
	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: status,
		Headers: map[string]string{
			"content-type":   "application/json; charset=utf-8",
			"content-length": fmt.Sprintf("%d", len(payload)),
		},
	})
	p.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, responseMeta))
	p.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, payload))
}

func (p *HTTPProxy) writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return
	}
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_ = conn.Write(ctx, websocket.MessageBinary, data)
}

func (p *HTTPProxy) writeErrorFrame(ctx context.Context, conn *websocket.Conn, streamID string, payload []byte) {
	p.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, payload))
}
```

- [ ] **Step 2: 修改 `V2Client` 使用 `HTTPProxy`**

修改 `V2Client` 结构体，移除 `http map[string]chan relaycore.Frame`，添加 `httpProxy *HTTPProxy`：

```go
type V2Client struct {
	cfg       config.RelayConfig
	app       *app.App
	router    *application.SessionRouter
	httpProxy *HTTPProxy    // 替换原来的 http map

	writeMu sync.Mutex
	mu      sync.Mutex
	ws      map[string]*relayStreamSocket
	p2p     map[string]*p2pPeer
}
```

修改 `NewV2`：
```go
func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	router := application.NewSessionRouter(application.Sessions())
	return &V2Client{
		cfg:       cfg,
		app:       application,
		router:    router,
		httpProxy: NewHTTPProxy(router, &writeMu),  // 注意：writeMu 是 V2Client 的字段，需要传指针
		ws:        make(map[string]*relayStreamSocket),
		p2p:       make(map[string]*p2pPeer),
	}
}
```

但这里有个问题：`NewV2` 中 `writeMu` 还没初始化——需要调整结构。将 `writeMu` 提前初始化或者改为在 `NewHTTPProxy` 中传入 `*V2Client`。更简单的方案：让 `HTTPProxy` 持有对 `V2Client` 的引用用于写帧。

重构方案：`HTTPProxy` 接收一个 `frameWriter` 接口：

在 `client_v2.go` 中定义：
```go
type frameWriter interface {
	writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame)
}
```

修改 `HTTPProxy`：
```go
type HTTPProxy struct {
	router *application.SessionRouter
	writer frameWriter
	mu     sync.Mutex
	streams map[string]chan relaycore.Frame
}

func NewHTTPProxy(router *application.SessionRouter, writer frameWriter) *HTTPProxy {
	return &HTTPProxy{
		router:  router,
		writer:  writer,
		streams: make(map[string]chan relaycore.Frame),
	}
}
```

`HTTPProxy` 中所有 `p.writeFrame(...)` 调用改为 `p.writer.writeFrame(...)`。

修改 `V2Client.handleFrame` 中的 HTTP 相关分支：
```go
func (client *V2Client) handleFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	switch frame.Type {
	case relaycore.FrameTypeHTTPHeaders:
		client.httpProxy.HandleHTTPHeaders(ctx, conn, frame)
	case relaycore.FrameTypeHTTPChunk:
		client.httpProxy.DeliverChunk(frame)
	// ... 其他 case 不变 ...
	}
}
```

删除 `V2Client` 的 `handleHTTPStream`、`respondHTTP`、`deliverHTTP` 方法。

- [ ] **Step 3: 运行测试验证**

```bash
cd go-core && go test ./internal/relay/...
```
期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add go-core/internal/relay/http_proxy.go go-core/internal/relay/client_v2.go
git commit -m "refactor: extract HTTPProxy from V2Client

Split HTTP request proxying (session CRUD over relay frames) into
a standalone HTTPProxy component. V2Client now delegates HTTP frame
handling to HTTPProxy via a frameWriter interface.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 拆分 V2Client — 分离 P2P 为独立组件

**背景：** `V2Client` 的 P2P/WebRTC 相关逻辑（`acceptP2POffer`、`p2pPeer`、`p2pDataChannelSocket`、`deliverP2PIce`、`closeAllP2P`）可以独立为 `P2PHandler`。

**Files:**
- Modify: `go-core/internal/relay/client_v2_p2p.go` — 重命名为独立组件
- Modify: `go-core/internal/relay/client_v2.go`

**Interfaces:**
- Produces: `P2PHandler` 结构体
- Consumes: `*application.SessionRouter`, `frameWriter`

- [ ] **Step 1: 重构 `client_v2_p2p.go` 为独立 `P2PHandler`**

将文件重构为独立的 `P2PHandler` 结构体。关键变更：
- `client *V2Client` 引用 → `h *P2PHandler` + `h.writer` + `h.router`
- `client.app.Sessions()` → `h.router.Manager()`
- `client.writeFrame(...)` → `h.writer.writeFrame(...)`
- `client.mu` → `h.mu`
- `client.p2p` → `h.peers`
- `client.storeP2PPeer(...)` → `h.storePeer(...)`
- `client.removeP2PPeer(...)` → `h.removePeer(...)`

具体变更如下（以 diff 形式描述，实际执行时逐行修改）：

```go
// 文件顶部：包名不变，添加 P2PHandler 结构体和方法
package relay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/pion/webrtc/v4"
	"nhooyr.io/websocket"

	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

const p2pAnswerGatherTimeout = 2 * time.Second

// P2PHandler 处理 WebRTC P2P 信令。
type P2PHandler struct {
	router *application.SessionRouter
	writer frameWriter
	mu     sync.Mutex
	peers  map[string]*p2pPeer
}

func NewP2PHandler(router *application.SessionRouter, writer frameWriter) *P2PHandler {
	return &P2PHandler{
		router: router,
		writer: writer,
		peers:  make(map[string]*p2pPeer),
	}
}

// AcceptOffer 处理 P2P offer 帧。
func (h *P2PHandler) AcceptOffer(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) error {
	if os.Getenv("WEBTERM_DISABLE_P2P") == "1" {
		return fmt.Errorf("p2p disabled")
	}
	var offer relaycore.P2POffer
	if err := json.Unmarshal(frame.Payload, &offer); err != nil {
		return fmt.Errorf("invalid p2p offer")
	}
	if offer.SDP == "" {
		return fmt.Errorf("missing p2p offer sdp")
	}

	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{{URLs: []string{"stun:stun.l.google.com:19302"}}},
	})
	if err != nil {
		return fmt.Errorf("create p2p peer: %w", err)
	}

	peerCtx, cancel := context.WithCancel(ctx)
	peer := &p2pPeer{streamID: frame.StreamID, pc: pc, cancel: cancel}
	h.storePeer(frame.StreamID, peer)
	cleanupOnError := true
	defer func() {
		if cleanupOnError {
			h.removePeer(frame.StreamID, peer)
			peer.close()
		}
	}()

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		switch state {
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			h.removePeer(frame.StreamID, peer)
			peer.close()
		}
	})

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() != "" && dc.Label() != "tunnel" {
			_ = dc.Close()
			return
		}
		socket := newP2PDataChannelSocket(dc)
		var startMuxOnce sync.Once
		startMux := func() {
			startMuxOnce.Do(func() {
				muxSession := mux.Serve(socket, &mux.ServeOpts{
					OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) (func(), error) {
						return mux.OpenSessionOrManager(ctx, h.router, vs, path, protocols)
					},
				})
				go func() {
					defer socket.Close()
					_ = muxSession.Run(peerCtx)
				}()
			})
		}
		dc.OnOpen(func() { startMux() })
		if dc.ReadyState() == webrtc.DataChannelStateOpen {
			startMux()
		}
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer.SDP}); err != nil {
		return fmt.Errorf("set p2p offer: %w", err)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("create p2p answer: %w", err)
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		return fmt.Errorf("set p2p answer: %w", err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(p2pAnswerGatherTimeout):
	case <-ctx.Done():
		return ctx.Err()
	}

	localDescription := pc.LocalDescription()
	if localDescription == nil {
		return fmt.Errorf("missing p2p local description")
	}
	payload, err := json.Marshal(relaycore.P2PAnswer{SDP: localDescription.SDP})
	if err != nil {
		return fmt.Errorf("encode p2p answer: %w", err)
	}
	h.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PAnswer, frame.StreamID, 0, payload))
	cleanupOnError = false
	return nil
}

// DeliverICE 将 ICE candidate 帧投递到对应 peer。
func (h *P2PHandler) DeliverICE(frame relaycore.Frame) {
	h.mu.Lock()
	peer := h.peers[frame.StreamID]
	h.mu.Unlock()
	if peer == nil || peer.pc == nil {
		return
	}
	candidate, err := decodeP2PIceCandidate(frame.Payload)
	if err != nil || candidate.Candidate == "" {
		return
	}
	_ = peer.pc.AddICECandidate(candidate)
}

// CloseAll 关闭所有活跃的 P2P 连接。
func (h *P2PHandler) CloseAll() {
	h.mu.Lock()
	peers := make([]*p2pPeer, 0, len(h.peers))
	for streamID, peer := range h.peers {
		delete(h.peers, streamID)
		peers = append(peers, peer)
	}
	h.mu.Unlock()
	for _, peer := range peers {
		peer.close()
	}
}

func (h *P2PHandler) storePeer(streamID string, peer *p2pPeer) {
	h.mu.Lock()
	old := h.peers[streamID]
	h.peers[streamID] = peer
	h.mu.Unlock()
	if old != nil && old != peer {
		old.close()
	}
}

func (h *P2PHandler) removePeer(streamID string, peer *p2pPeer) {
	h.mu.Lock()
	if current := h.peers[streamID]; current == peer {
		delete(h.peers, streamID)
	}
	h.mu.Unlock()
}

// --- 以下类型和方法从原 client_v2_p2p.go 保持不变 ---

// p2pPeer 保持不变
type p2pPeer struct {
	streamID string
	pc       *webrtc.PeerConnection
	cancel   context.CancelFunc
	once     sync.Once
}

func (peer *p2pPeer) close() { /* 不变 */ }

// p2pDataChannelSocket 保持不变（已在 Task 2 中移除 Subprotocol()）
type p2pDataChannelSocket struct { /* 不变 */ }
func newP2PDataChannelSocket(dc *webrtc.DataChannel) *p2pDataChannelSocket { /* 不变 */ }
func (socket *p2pDataChannelSocket) Read(...) { /* 不变 */ }
func (socket *p2pDataChannelSocket) Write(...) { /* 不变 */ }
func (socket *p2pDataChannelSocket) Close() error { /* 不变 */ }

// decodeP2PIceCandidate 保持不变
func decodeP2PIceCandidate(payload []byte) (webrtc.ICECandidateInit, error) { /* 不变 */ }
```

- [ ] **Step 2: 修改 `V2Client` 使用 `P2PHandler`**

```go
type V2Client struct {
	cfg       config.RelayConfig
	app       *app.App
	router    *application.SessionRouter
	httpProxy *HTTPProxy
	p2p       *P2PHandler    // 替换原来的 p2p map[string]*p2pPeer

	writeMu sync.Mutex
	mu      sync.Mutex
	ws      map[string]*relayStreamSocket
}
```

修改 `NewV2`：
```go
func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	router := application.NewSessionRouter(application.Sessions())
	client := &V2Client{
		cfg:    cfg,
		app:    application,
		router: router,
		ws:     make(map[string]*relayStreamSocket),
	}
	client.httpProxy = NewHTTPProxy(router, client)
	client.p2p = NewP2PHandler(router, client)
	return client
}
```

修改 `handleFrame`：
```go
case relaycore.FrameTypeP2POffer:
    if err := client.p2p.AcceptOffer(ctx, conn, frame); err != nil {
        payload, _ := json.Marshal(relaycore.P2PUnavailable{Message: err.Error()})
        client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PUnavailable, frame.StreamID, 0, payload))
    }
case relaycore.FrameTypeP2PIce:
    client.p2p.DeliverICE(frame)
```

修改 `readLoop` 中的 `defer client.closeAllP2P()` → `defer client.p2p.CloseAll()`。

删除 `V2Client` 的 `acceptP2POffer`、`storeP2PPeer`、`removeP2PPeer`、`closeAllP2P`、`deliverP2PIce`、`handleP2POfferV2` 方法。

- [ ] **Step 3: 运行测试验证**

```bash
cd go-core && go test ./internal/relay/...
```
期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add go-core/internal/relay/client_v2_p2p.go go-core/internal/relay/client_v2.go
git commit -m "refactor: extract P2PHandler from V2Client

Split P2P/WebRTC signaling into a standalone P2PHandler component.
V2Client now delegates offer/ICE handling to P2PHandler via the
shared frameWriter interface.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 拆分 V2Client — 分离流多路复用为独立组件

**背景：** `V2Client` 剩余的 `ws map` 管理、`handleStreamOpen`、`deliverWS`、`closeWS`、`relayStreamSocket` 可以独立为 `StreamMultiplexer`。

**Files:**
- Create: `go-core/internal/relay/stream_mux.go`
- Modify: `go-core/internal/relay/client_v2.go`

**Interfaces:**
- Produces: `StreamMultiplexer` 结构体
- Consumes: `*application.SessionRouter`, `frameWriter`

- [ ] **Step 1: 创建 `relay/stream_mux.go`**

将 `relayStreamSocket` 和流管理逻辑从 `client_v2.go` 移到新文件：

```go
package relay

import (
	"context"
	"encoding/json"
	"errors"
	"sync"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

// StreamMultiplexer 管理 relay 侧的 WebSocket 流多路复用。
// 每个 stream 对应一个到 relay server 的逻辑通道。
type StreamMultiplexer struct {
	router *application.SessionRouter
	writer frameWriter
	mu     sync.Mutex
	streams map[string]*relayStreamSocket
}

func NewStreamMultiplexer(router *application.SessionRouter, writer frameWriter) *StreamMultiplexer {
	return &StreamMultiplexer{
		router:  router,
		writer:  writer,
		streams: make(map[string]*relayStreamSocket),
	}
}

// OpenStream 处理 StreamOpen 帧——创建新的 relay stream。
func (m *StreamMultiplexer) OpenStream(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	var route relaycore.StreamRoute
	if err := json.Unmarshal(frame.Payload, &route); err != nil {
		m.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, frame.StreamID, 0, []byte("invalid stream route")))
		return
	}
	socket := newRelayStreamSocket(frame.StreamID, route.Subprotocol, m, conn)
	start, err := m.router.RouteOpen(ctx, socket, route.Path, []string{route.Subprotocol})
	if err != nil {
		m.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, frame.StreamID, 0, []byte(err.Error())))
		return
	}
	m.mu.Lock()
	m.streams[frame.StreamID] = socket
	m.mu.Unlock()
	if start != nil {
		go start()
	}
}

// DeliverWS 将 WS 数据帧投递到对应 stream。
func (m *StreamMultiplexer) DeliverWS(frame relaycore.Frame) {
	m.mu.Lock()
	socket := m.streams[frame.StreamID]
	m.mu.Unlock()
	if socket == nil {
		return
	}
	binary := frame.Type == relaycore.FrameTypeWSBinary
	socket.Emit(frame.Payload, binary)
}

// CloseStream 关闭指定 stream。
func (m *StreamMultiplexer) CloseStream(streamID string, notifyRemote bool) {
	m.mu.Lock()
	socket := m.streams[streamID]
	delete(m.streams, streamID)
	m.mu.Unlock()
	if socket != nil {
		socket.close(notifyRemote)
	}
}

// relayStreamSocket 实现 session.Socket，通过 relay 帧通信。
type relayStreamSocket struct {
	id       string
	protocol string
	mux      *StreamMultiplexer
	conn     *websocket.Conn
	incoming chan relayStreamMessage
	done     chan struct{}
	once     sync.Once
}

type relayStreamMessage struct {
	messageType session.MessageType
	payload     []byte
}

func newRelayStreamSocket(id string, protocolName string, mux *StreamMultiplexer, conn *websocket.Conn) *relayStreamSocket {
	return &relayStreamSocket{
		id:       id,
		protocol: protocolName,
		mux:      mux,
		conn:     conn,
		incoming: make(chan relayStreamMessage, 256),
		done:     make(chan struct{}),
	}
}

func (s *relayStreamSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case <-s.done:
		return 0, nil, errors.New("relay stream socket closed")
	case msg := <-s.incoming:
		return msg.messageType, msg.payload, nil
	}
}

func (s *relayStreamSocket) Write(ctx context.Context, messageType session.MessageType, data []byte) error {
	frameType := relaycore.FrameTypeWSText
	if messageType == session.MessageBinary {
		frameType = relaycore.FrameTypeWSBinary
	}
	frame := relaycore.NewFrame(frameType, s.id, 0, data)
	encoded, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return err
	}
	return s.mux.writer.writeRaw(ctx, s.conn, encoded)
}

func (s *relayStreamSocket) Close() error {
	s.close(true)
	return nil
}

func (s *relayStreamSocket) close(notifyRemote bool) {
	s.once.Do(func() {
		close(s.done)
		s.mux.mu.Lock()
		delete(s.mux.streams, s.id)
		s.mux.mu.Unlock()
		if notifyRemote {
			s.mux.writer.writeFrame(context.Background(), s.conn, relaycore.NewFrame(relaycore.FrameTypeStreamClose, s.id, 0, nil))
		}
	})
}

func (s *relayStreamSocket) Emit(payload []byte, binary bool) bool {
	messageType := session.MessageText
	if binary {
		messageType = session.MessageBinary
	}
	select {
	case <-s.done:
		return false
	case s.incoming <- relayStreamMessage{messageType: messageType, payload: payload}:
		return true
	default:
		_ = s.Close()
		return false
	}
}
```

- [ ] **Step 2: 更新 `frameWriter` 接口以支持 `writeRaw`**

在 `client_v2.go` 中扩展 `frameWriter` 接口：
```go
type frameWriter interface {
	writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame)
	writeRaw(ctx context.Context, conn *websocket.Conn, data []byte) error
}
```

在 `V2Client` 上添加 `writeRaw` 方法：
```go
func (client *V2Client) writeRaw(ctx context.Context, conn *websocket.Conn, data []byte) error {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	return conn.Write(writeCtx, websocket.MessageBinary, data)
}
```

- [ ] **Step 3: 修改 `V2Client` 使用 `StreamMultiplexer`**

```go
type V2Client struct {
	cfg     config.RelayConfig
	app     *app.App
	router  *application.SessionRouter
	http    *HTTPProxy
	p2p     *P2PHandler
	streams *StreamMultiplexer   // 替换原来的 ws map

	writeMu sync.Mutex
}
```

修改 `NewV2`：
```go
func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	router := application.NewSessionRouter(application.Sessions())
	client := &V2Client{
		cfg:    cfg,
		app:    application,
		router: router,
	}
	client.http = NewHTTPProxy(router, client)
	client.p2p = NewP2PHandler(router, client)
	client.streams = NewStreamMultiplexer(router, client)
	return client
}
```

修改 `handleFrame`：
```go
case relaycore.FrameTypeStreamOpen:
    client.streams.OpenStream(ctx, conn, frame)
case relaycore.FrameTypeWSText, relaycore.FrameTypeWSBinary:
    client.streams.DeliverWS(frame)
case relaycore.FrameTypeStreamClose, relaycore.FrameTypeStreamError:
    client.streams.CloseStream(frame.StreamID, false)
```

删除 `V2Client` 的 `handleStreamOpen`、`deliverWS`、`closeWS`、`openSessionOrManager`（已在 Task 1 中删除）、`routeMemoryAPI`（已在 Task 1 中删除）方法。

删除 `V2Client` 的 `ws map[string]*relayStreamSocket` 字段和 `mu sync.Mutex` 字段。

- [ ] **Step 4: 运行测试验证**

```bash
cd go-core && go test ./internal/relay/...
```
期望：全部通过。

- [ ] **Step 5: 运行完整 smoke test**

```bash
cd go-core && go test ./cmd/webterm-relay-flow-smoke/... ./cmd/webterm-relay-e2e-smoke/...
```
期望：全部通过。

- [ ] **Step 6: Commit**

```bash
git add go-core/internal/relay/stream_mux.go go-core/internal/relay/client_v2.go
git commit -m "refactor: extract StreamMultiplexer from V2Client

Split WebSocket stream multiplexing into a standalone component.
V2Client is now a thin coordinator delegating to HTTPProxy,
P2PHandler, and StreamMultiplexer. The V2Client struct reduced
from 9 fields to 6, with no more map-management methods.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: 拆分 session 包 — 分离 PTY 进程管理

**背景：** `session/terminal.go` 中的 PTY 进程管理（`readLoop`、`waitLoop`、`shellCommand`、`validateCWD`、`buildEnv`）与终端模拟渲染逻辑混在一起。

**策略：** 将 PTY 进程管理提取到 `internal/infrastructure/pty/` 包。

**Files:**
- Create: `go-core/internal/infrastructure/pty/process.go`
- Modify: `go-core/internal/session/terminal.go`

**Interfaces:**
- Produces: `pty.Process` 结构体
- Consumes: `session.TerminalSession` 将 PTY 操作委托给 `pty.Process`

- [ ] **Step 1: 创建 `infrastructure/pty/process.go`**

```go
package pty

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/creack/pty"
)

const (
	DefaultCols = 100
	DefaultRows = 30
)

// Process 封装 OS 进程 + PTY 的生命周期管理。
type Process struct {
	cmd     *exec.Cmd
	ptmx    *os.File
	cols    int
	rows    int
	command string
	cwd     string
}

// Options 定义进程启动参数。
type Options struct {
	CWD     string
	Command string
	Cols    int
	Rows    int
}

// Start 启动一个带 PTY 的新进程。
func Start(opts Options) (*Process, error) {
	cols := opts.Cols
	if cols <= 0 {
		cols = DefaultCols
	}
	rows := opts.Rows
	if rows <= 0 {
		rows = DefaultRows
	}
	cwd, err := validateCWD(opts.CWD)
	if err != nil {
		return nil, err
	}
	shellCmd, args, err := resolveShell(opts.Command)
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(shellCmd, args...)
	cmd.Dir = cwd
	cmd.Env = buildEnv(os.Environ())
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{
		Cols: uint16(cols),
		Rows: uint16(rows),
	})
	if err != nil {
		return nil, err
	}
	return &Process{
		cmd:     cmd,
		ptmx:    ptmx,
		cols:    cols,
		rows:    rows,
		command: strings.Join(append([]string{shellCmd}, args...), " "),
		cwd:     cwd,
	}, nil
}

// Read 从 PTY 读取输出。
func (p *Process) Read(buf []byte) (int, error) {
	return p.ptmx.Read(buf)
}

// Write 向 PTY 写入输入。
func (p *Process) Write(data []byte) (int, error) {
	return p.ptmx.Write(data)
}

// Resize 调整 PTY 窗口大小。
func (p *Process) Resize(cols int, rows int) error {
	if cols < 10 || rows < 5 {
		return nil
	}
	if cols > 500 {
		cols = 500
	}
	if rows > 200 {
		rows = 200
	}
	p.cols = cols
	p.rows = rows
	return pty.Setsize(p.ptmx, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

// Wait 等待进程退出，返回退出码。
func (p *Process) Wait() (int, error) {
	err := p.cmd.Wait()
	code := 0
	if err != nil {
		code = 1
	}
	return code, err
}

// Kill 强制终止进程。
func (p *Process) Kill() {
	if p.cmd != nil && p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
	}
}

// Close 关闭 PTY。
func (p *Process) Close() error {
	return p.ptmx.Close()
}

// PTY 返回底层的 PTY 文件描述符（仅用于需要直接操作的场景）。
func (p *Process) PTY() *os.File {
	return p.ptmx
}

// Command 返回完整命令行。
func (p *Process) Command() string {
	return p.command
}

// CWD 返回工作目录。
func (p *Process) CWD() string {
	return p.cwd
}

// Cols 返回列数。
func (p *Process) Cols() int { return p.cols }

// Rows 返回行数。
func (p *Process) Rows() int { return p.rows }

// --- helpers ---

func validateCWD(cwd string) (string, error) {
	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return "", fmt.Errorf("cwd does not exist or is not accessible: %s", abs)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("cwd is not a directory: %s", abs)
	}
	return abs, nil
}

func resolveShell(command string) (string, []string, error) {
	if command != "" {
		return command, nil, nil
	}
	if runtime.GOOS == "windows" {
		if comspec := os.Getenv("ComSpec"); comspec != "" {
			return comspec, nil, nil
		}
		return "cmd.exe", nil, nil
	}
	candidates := []string{os.Getenv("SHELL"), "/bin/zsh", "/bin/bash", "/bin/sh"}
	for _, c := range candidates {
		if c == "" {
			continue
		}
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			return c, nil, nil
		}
	}
	return "", nil, errors.New("no executable shell found")
}

func buildEnv(source []string) []string {
	env := append([]string(nil), source...)
	env = setEnv(env, "TERM", "xterm-256color")
	env = setEnv(env, "COLORTERM", "truecolor")
	env = setEnv(env, "WEBTERM", "1")
	return env
}

func setEnv(env []string, key string, value string) []string {
	prefix := key + "="
	for i, item := range env {
		if strings.HasPrefix(item, prefix) {
			env[i] = prefix + value
			return env
		}
	}
	return append(env, prefix+value)
}
```

- [ ] **Step 2: 修改 `session/terminal.go` 使用 `pty.Process`**

修改 `TerminalSession` 结构体，用 `*pty.Process` 替换 `pty *os.File` 和 `cmd *exec.Cmd`：

```go
type TerminalSession struct {
	mu             sync.RWMutex
	id             string
	instance       string
	name           string
	termTitle      string
	cwd            string
	liveCwd        string
	command        string
	status         string
	cols           int
	rows           int
	createdAt      time.Time
	activeAt       time.Time
	ring           *EventRing
	screen         *ScreenState
	process        *pty.Process        // 替换原来的 pty *os.File + cmd *exec.Cmd
	clients        map[*Client]struct{}
	onTitleChanged func()
	titleChanged   bool
}
```

修改 `NewTerminalSession`：
```go
func NewTerminalSession(options TerminalOptions) (*TerminalSession, error) {
	now := time.Now().UTC()
	cols := options.Cols
	if cols <= 0 {
		cols = DefaultCols
	}
	rows := options.Rows
	if rows <= 0 {
		rows = DefaultRows
	}

	process, err := pty.Start(pty.Options{
		CWD:     options.CWD,
		Command: options.Command,
		Cols:    cols,
		Rows:    rows,
	})
	if err != nil {
		return nil, err
	}

	var terminal *TerminalSession
	titleProvider := &terminalTitleProvider{
		onTitle: func(title string) {
			if terminal != nil {
				terminal.termTitle = title
				terminal.titleChanged = true
			}
		},
	}

	terminal = &TerminalSession{
		id:             options.ID,
		instance:       randomID(),
		name:           normalize(options.Name),
		cwd:            process.CWD(),
		command:        process.Command(),
		status:         StatusRunning,
		cols:           cols,
		rows:           rows,
		createdAt:      now,
		activeAt:       now,
		ring:           NewEventRing(0, 0),
		screen:         NewScreenState(rows, cols, process.PTY(), titleProvider),
		process:        process,
		clients:        make(map[*Client]struct{}),
		onTitleChanged: options.OnTitle,
	}
	go terminal.readLoop()
	go terminal.waitLoop()
	return terminal, nil
}
```

修改 `readLoop`：
```go
func (terminal *TerminalSession) readLoop() {
	buf := make([]byte, 32*1024)
	for {
		n, err := terminal.process.Read(buf)
		if n > 0 {
			bytes := append([]byte(nil), buf[:n]...)
			frame := terminal.PushOutput(bytes)
			terminal.broadcastOutput(frame)
		}
		if err != nil {
			if err != io.EOF {
				terminal.markClosed()
			}
			return
		}
	}
}
```

修改 `waitLoop`：
```go
func (terminal *TerminalSession) waitLoop() {
	code, _ := terminal.process.Wait()
	terminal.markClosed()
	terminal.broadcastExit(code)
}
```

修改 `WriteInput`：
```go
func (terminal *TerminalSession) WriteInput(data []byte) error {
	terminal.mu.Lock()
	terminal.touchLocked()
	terminal.mu.Unlock()
	_, err := terminal.process.Write(data)
	return err
}
```

修改 `Resize`：
```go
func (terminal *TerminalSession) Resize(cols int, rows int) error {
	if err := terminal.process.Resize(cols, rows); err != nil {
		return err
	}
	terminal.mu.Lock()
	terminal.cols = terminal.process.Cols()
	terminal.rows = terminal.process.Rows()
	if terminal.screen != nil {
		terminal.screen.Resize(terminal.rows, terminal.cols)
	}
	terminal.touchLocked()
	terminal.mu.Unlock()
	return nil
}
```

修改 `Close`：
```go
func (terminal *TerminalSession) Close() {
	terminal.mu.Lock()
	if terminal.status == StatusClosed {
		terminal.mu.Unlock()
		return
	}
	terminal.status = StatusClosed
	terminal.touchLocked()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.Unlock()

	if terminal.process != nil {
		_ = terminal.process.Close()
		terminal.process.Kill()
	}
	for _, client := range clients {
		client.Close()
	}
}
```

删除 `session/terminal.go` 中的 `shellCommand`、`validateCWD`、`buildEnv`、`setEnv` 函数（已移到 `pty` 包）。

- [ ] **Step 3: 运行测试验证**

```bash
cd go-core && go test ./internal/infrastructure/pty/... ./internal/session/...
```
期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add go-core/internal/infrastructure/pty/process.go go-core/internal/session/terminal.go
git commit -m "refactor: extract PTY process management to infrastructure/pty

Move OS process + PTY lifecycle (Start/Read/Write/Resize/Wait/Kill)
from session/terminal.go into a standalone infrastructure/pty.Process.
TerminalSession now delegates all PTY operations to pty.Process.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 拆分 session 包 — 分离终端模拟渲染

**背景：** `session/screen.go` 中的 `ScreenState` 封装了 `go-headless-term` 终端模拟器。可以提取为独立的 `infrastructure/emulator` 包。

**Files:**
- Create: `go-core/internal/infrastructure/emulator/screen.go`
- Modify: `go-core/internal/session/screen.go` — 改为类型别名指向新包
- Modify: `go-core/internal/session/terminal.go` — 更新 import

**Interfaces:**
- Produces: `emulator.Screen` 结构体
- Consumes: `session.TerminalSession` 使用 `emulator.Screen`

- [ ] **Step 1: 创建 `infrastructure/emulator/screen.go`**

将 `session/screen.go` 的完整实现移到新包。注意 `ScreenState` 重命名为 `Screen`，`ScreenDirtyCell` 重命名为 `DirtyCell`：

```go
package emulator

import (
	"fmt"
	"image/color"
	"io"
	"strings"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

const screenScrollbackLines = 10000

// TitleProvider 由 headless-term 定义，重新导出以解耦。
type TitleProvider = headlessterm.TitleProvider

// Snapshot 是终端屏幕快照的类型别名。
type Snapshot = headlessterm.Snapshot

// DirtyCell 表示一个脏单元格。
type DirtyCell struct {
	Row  int    `json:"row"`
	Col  int    `json:"col"`
	Char string `json:"char"`
}

// Delta 表示自上次查询以来的屏幕变化。
type Delta struct {
	Seq   uint64      `json:"seq"`
	Cells []DirtyCell `json:"cells"`
}

// Screen 封装 headless 终端模拟器，提供屏幕快照和增量渲染。
type Screen struct {
	mu       sync.Mutex
	terminal *headlessterm.Terminal
}

// NewScreen 创建新的屏幕状态。
func NewScreen(rows int, cols int, responses io.Writer, title TitleProvider) *Screen {
	if rows <= 0 {
		rows = 30
	}
	if cols <= 0 {
		cols = 100
	}
	options := []headlessterm.Option{
		headlessterm.WithSize(rows, cols),
		headlessterm.WithScrollback(headlessterm.NewMemoryScrollback(screenScrollbackLines)),
	}
	if responses != nil {
		options = append(options, headlessterm.WithPTYWriter(responses))
	}
	if title != nil {
		options = append(options, headlessterm.WithTitle(title))
	}
	return &Screen{terminal: headlessterm.New(options...)}
}

func (s *Screen) Write(data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.terminal.Write(data)
	return err
}

func (s *Screen) WriteAndWorkingDirectoryPath(data []byte) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.terminal.Write(data)
	return s.terminal.WorkingDirectoryPath(), err
}

func (s *Screen) WorkingDirectoryPath() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.terminal.WorkingDirectoryPath()
}

func (s *Screen) Resize(rows int, cols int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.terminal.Resize(rows, cols)
}

func (s *Screen) Snapshot(detail headlessterm.SnapshotDetail) *headlessterm.Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.terminal.Snapshot(detail)
}

func (s *Screen) Text() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.terminal.String()
}

func (s *Screen) DirtyDelta(seq uint64) Delta {
	s.mu.Lock()
	defer s.mu.Unlock()
	positions := s.terminal.DirtyCells()
	cells := make([]DirtyCell, 0, len(positions))
	for _, pos := range positions {
		cell := s.terminal.Cell(pos.Row, pos.Col)
		if cell == nil {
			continue
		}
		cells = append(cells, DirtyCell{Row: pos.Row, Col: pos.Col, Char: string(cell.Char)})
	}
	s.terminal.ClearDirty()
	return Delta{Seq: seq, Cells: cells}
}

// TerminalTitleProvider 实现 headlessterm.TitleProvider。
type TerminalTitleProvider struct {
	OnTitle func(string)
	stack   []string
	current string
}

func (p *TerminalTitleProvider) SetTitle(title string) {
	p.current = title
	if p.OnTitle != nil {
		p.OnTitle(title)
	}
}

func (p *TerminalTitleProvider) PushTitle() {
	p.stack = append(p.stack, p.current)
}

func (p *TerminalTitleProvider) PopTitle() {
	if len(p.stack) == 0 {
		return
	}
	last := len(p.stack) - 1
	title := p.stack[last]
	p.stack = p.stack[:last]
	p.SetTitle(title)
}

// AnsiText 重建完整 ANSI 文本（SGR 样式 + 光标定位）。
// 实现与 session/screen.go 的 AnsiText() 完全一致（约200行），在此省略以保持计划简洁。
// 实际实现时，将 session/screen.go 的 AnsiText() 方法体完整复制到此处，
// 并将所有 screen. → s.、ScreenState → Screen、ScreenDirtyCell → DirtyCell。
func (s *Screen) AnsiText() string {
	// 完整实现复制自 session/screen.go:363-605
	// 关键替换：
	//   screen.mu → s.mu
	//   screen.terminal → s.terminal
	//   ScreenDirtyCell → DirtyCell
	//   terminalTitleProvider → TerminalTitleProvider
	//   所有辅助函数 (colorEquals, appendFgSGR, appendBgSGR 等) 一并复制
	_ = s // placeholder — actual implementation is the full body from session/screen.go
	return ""
}

// 以下辅助函数从 session/screen.go 完整复制（约100行），函数名不变：
//   colorEquals, appendFgSGR, appendBgSGR, appendUnderlineColorSGR,
//   isDefaultFg, isDefaultBg, isDefaultStyle, lastActiveCol, lastActiveRow
```

**注意：** 实际实现时，`AnsiText()` 方法体和所有辅助函数直接从 `session/screen.go` 复制，不需要重写。以上省略是为了避免在计划中重复 300+ 行几乎相同的代码。关键替换规则：
- `screen.mu` → `s.mu`
- `screen.terminal` → `s.terminal`  
- `ScreenState` → `Screen`
- `ScreenDirtyCell` → `DirtyCell`
- `terminalTitleProvider` → `TerminalTitleProvider`

- [ ] **Step 2: 修改 `session/screen.go` 为类型别名**

将 `session/screen.go` 的完整实现替换为以下内容：

```go
package session

import (
	"io"

	"webterm/go-core/internal/infrastructure/emulator"
)

// ScreenState 是 emulator.Screen 的类型别名，保持向后兼容。
type ScreenState = emulator.Screen

// NewScreenState 创建新的屏幕状态。
func NewScreenState(rows int, cols int, responses io.Writer, title emulator.TitleProvider) *ScreenState {
	return emulator.NewScreen(rows, cols, responses, title)
}

// ScreenDirtyCell 是 emulator.DirtyCell 的别名。
type ScreenDirtyCell = emulator.DirtyCell

// ScreenDelta 是 emulator.Delta 的别名。
type ScreenDelta = emulator.Delta

// ScreenSnapshot 是 emulator.Snapshot 的别名。
type ScreenSnapshot = emulator.Snapshot
```

删除 `session/screen.go` 中的所有其他代码（约550行），只保留以上别名。

- [ ] **Step 3: 更新 `session/terminal.go` 中的 titleProvider**

在 `session/terminal.go` 中，将 `terminalTitleProvider` 替换为 `emulator.TerminalTitleProvider`：

```go
// 修改前：
titleProvider := &terminalTitleProvider{
    onTitle: func(title string) { ... },
}

// 修改后：
titleProvider := &emulator.TerminalTitleProvider{
    OnTitle: func(title string) { ... },
}
```

删除 `session/terminal.go` 中的 `terminalTitleProvider` 类型定义（如果存在——当前它在 `session/screen.go` 中）。

- [ ] **Step 3: 调整 `session/terminal.go` 的 import**

在 `terminal.go` 中添加：
```go
import (
    // ... 已有 imports ...
    "webterm/go-core/internal/infrastructure/emulator"
)
```

将 `NewScreenState(...)` 调用改为 `emulator.NewScreen(...)`，或继续使用别名。

- [ ] **Step 4: 运行测试验证**

```bash
cd go-core && go test ./internal/infrastructure/emulator/... ./internal/session/...
```
期望：全部通过。

- [ ] **Step 5: Commit**

```bash
git add go-core/internal/infrastructure/emulator/screen.go go-core/internal/session/screen.go go-core/internal/session/terminal.go
git commit -m "refactor: extract terminal emulator to infrastructure/emulator

Move ScreenState (headless terminal emulator wrapper) from
session/screen.go to a standalone infrastructure/emulator.Screen.
session/screen.go now provides type aliases for backward compatibility.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 拆分 session 包 — 分离事件环形缓冲

**背景：** `session/event_ring.go` 是通用的环形缓冲区，不依赖 session 包的任何其他类型。

**Files:**
- Create: `go-core/internal/infrastructure/eventring/ring.go`
- Modify: `go-core/internal/session/event_ring.go` — 改为类型别名
- Modify: `go-core/internal/session/terminal.go` — 更新 import

**Interfaces:**
- Produces: `eventring.Ring` 结构体
- Consumes: `session.TerminalSession`

- [ ] **Step 1: 创建 `infrastructure/eventring/ring.go`**

将 `session/event_ring.go` 的完整实现移到新包。注意原实现使用 head-based ring buffer（非标准环形），`EventRing` 重命名为 `Ring`，`EventFrame` 重命名为 `Frame`：

```go
package eventring

const (
	DefaultMaxFrames = 20000
	DefaultMaxBytes  = 5 * 1024 * 1024
)

// Frame 是环形缓冲区中的一帧终端输出事件。
type Frame struct {
	Seq     uint64
	Bytes   []byte
	ByteLen int
}

// Ring 是 head-based 环形缓冲区，存储终端输出事件帧。
// 不是线程安全的——调用方负责同步。
type Ring struct {
	maxFrames int
	maxBytes  int
	frames    []Frame
	head      int
	bytes     int
	nextSeq   uint64
}

// New 创建新的事件环形缓冲。
func New(maxFrames int, maxBytes int) *Ring {
	if maxFrames <= 0 {
		maxFrames = DefaultMaxFrames
	}
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	return &Ring{
		maxFrames: maxFrames,
		maxBytes:  maxBytes,
		nextSeq:   1,
	}
}

// Push 添加一帧数据，返回克隆的帧。
func (r *Ring) Push(data []byte) Frame {
	bytes := append([]byte(nil), data...)
	frame := Frame{
		Seq:     r.nextSeq,
		Bytes:   bytes,
		ByteLen: len(bytes),
	}
	r.nextSeq++
	r.frames = append(r.frames, frame)
	r.bytes += frame.ByteLen
	r.trim()
	return cloneFrame(frame)
}

// After 返回 seq 之后的所有帧（不含 seq 本身）。
func (r *Ring) After(seq uint64) []Frame {
	active := r.activeFrames()
	low := 0
	high := len(active)
	for low < high {
		mid := (low + high) / 2
		if active[mid].Seq <= seq {
			low = mid + 1
		} else {
			high = mid
		}
	}
	return cloneFrames(active[low:])
}

// CanReplayFrom 检查是否可以从指定 seq 重放。
func (r *Ring) CanReplayFrom(seq uint64) bool {
	if r.Len() == 0 {
		return true
	}
	return seq >= r.frames[r.head].Seq-1
}

// LatestSeq 返回最新的 seq 号。
func (r *Ring) LatestSeq() uint64 {
	if r.nextSeq == 0 {
		return 0
	}
	return r.nextSeq - 1
}

// Len 返回活跃帧数量。
func (r *Ring) Len() int {
	return len(r.frames) - r.head
}

func (r *Ring) trim() {
	for r.Len() > r.maxFrames || r.bytes > r.maxBytes {
		frame := r.frames[r.head]
		r.bytes -= frame.ByteLen
		r.head++
	}
	r.compactIfNeeded()
}

func (r *Ring) activeFrames() []Frame {
	if r.head == 0 {
		return r.frames
	}
	return r.frames[r.head:]
}

func (r *Ring) compactIfNeeded() {
	if r.head == 0 {
		return
	}
	if r.head < 1024 && r.head*2 < len(r.frames) {
		return
	}
	r.frames = append([]Frame(nil), r.frames[r.head:]...)
	r.head = 0
}

func cloneFrames(frames []Frame) []Frame {
	out := make([]Frame, len(frames))
	for i, f := range frames {
		out[i] = cloneFrame(f)
	}
	return out
}

func cloneFrame(frame Frame) Frame {
	frame.Bytes = append([]byte(nil), frame.Bytes...)
	return frame
}
```

- [ ] **Step 2: 修改 `session/event_ring.go` 为类型别名**

```go
package session

import "webterm/go-core/internal/infrastructure/eventring"

// EventRing 是 eventring.Ring 的类型别名。
type EventRing = eventring.Ring

// EventFrame 是 eventring.Frame 的类型别名。
type EventFrame = eventring.Frame

// NewEventRing 创建新的事件环形缓冲。
var NewEventRing = eventring.New
```

- [ ] **Step 3: 运行测试验证**

```bash
cd go-core && go test ./internal/infrastructure/eventring/... ./internal/session/...
```
期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add go-core/internal/infrastructure/eventring/ring.go go-core/internal/session/event_ring.go
git commit -m "refactor: extract event ring buffer to infrastructure/eventring

Move the generic ring buffer implementation from session/event_ring.go
to a standalone infrastructure/eventring.Ring. session/event_ring.go
now provides type aliases for backward compatibility.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 引入 Runner 注册机制

**背景：** `runtime/supervisor.go` 的 `DefaultFactory` 硬编码了 direct/relay 两种模式。本任务引入 Runner 注册机制，允许在不修改 `DefaultFactory` 的情况下添加新模式。

**Files:**
- Modify: `go-core/internal/runtime/supervisor.go`

**Interfaces:**
- Produces: `RunnerRegistry` 结构体
- Modifies: `DefaultFactory` 使用注册表

- [ ] **Step 1: 在 `supervisor.go` 中添加 `RunnerRegistry`**

在 `supervisor.go` 末尾添加：

```go
// RunnerRegistry 按运行模式注册 Runner 工厂。
type RunnerRegistry struct {
	factories map[string]Factory
}

// NewRunnerRegistry 创建包含默认 direct/relay 工厂的注册表。
func NewRunnerRegistry() *RunnerRegistry {
	r := &RunnerRegistry{factories: make(map[string]Factory)}
	r.Register(config.ModeDirect, func(cfg config.Config, app *app.App) (Runner, error) {
		return direct.New(cfg.Direct, app), nil
	})
	r.Register(config.ModeRelay, func(cfg config.Config, app *app.App) (Runner, error) {
		return RunnerFunc(func(ctx context.Context) error {
			cfg.Relay.Protocol = config.NormalizeRelayProtocol(cfg.Relay.Protocol)
			return relay.NewV2(cfg.Relay, app).Run(ctx)
		}), nil
	})
	return r
}

// Register 注册一个运行模式。
func (r *RunnerRegistry) Register(mode string, factory Factory) {
	r.factories[mode] = factory
}

// Create 根据模式创建 Runner。模式未注册时返回错误。
func (r *RunnerRegistry) Create(cfg config.Config, app *app.App) (Runner, error) {
	factory, ok := r.factories[cfg.Mode]
	if !ok {
		return nil, fmt.Errorf("unsupported mode: %s", cfg.Mode)
	}
	return factory(cfg, app)
}

// DefaultFactory 使用默认注册表，保持向后兼容。
var defaultRegistry = NewRunnerRegistry()

func DefaultFactory(cfg config.Config, app *app.App) (Runner, error) {
	return defaultRegistry.Create(cfg, app)
}
```

需要在 `supervisor.go` 顶部添加 `"fmt"` import。

- [ ] **Step 2: 运行测试验证**

```bash
cd go-core && go test ./internal/runtime/...
```
期望：全部通过。

- [ ] **Step 3: Commit**

```bash
git add go-core/internal/runtime/supervisor.go
git commit -m "feat: add RunnerRegistry for pluggable runtime modes

Replace the hardcoded switch in DefaultFactory with a RunnerRegistry
that supports Register/Create. New modes can be added without
modifying existing code. DefaultFactory behavior is unchanged.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: 最终验证和清理

- [ ] **Step 1: 运行全部测试**

```bash
cd go-core && go test ./...
```
期望：所有测试通过。

- [ ] **Step 2: 运行全部 smoke tests**

```bash
cd go-core
go test ./cmd/webterm-flow-smoke/...
go test ./cmd/webterm-relay-flow-smoke/...
go test ./cmd/webterm-relay-e2e-smoke/...
go test ./cmd/webterm-relay-storm-smoke/...
```
期望：全部通过。

- [ ] **Step 3: 编译验证**

```bash
cd go-core
go build ./cmd/webterm-agent
go build ./cmd/webterm-relay
```
期望：编译成功。

- [ ] **Step 4: 检查不再使用的 import 和死代码**

```bash
cd go-core && go vet ./...
```
期望：无警告。

- [ ] **Step 5: 最终 Commit**

```bash
git add -A go-core/
git commit -m "chore: final cleanup after modular refactor

All tests pass. No dead code or unused imports remain.
Final directory structure reflects the new layered architecture.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 重构后的目录结构

```
go-core/internal/
├── application/                    # 应用服务层
│   └── session_router.go          # 共享的 session 路径分发 + CRUD
├── app/
│   └── app.go                     # 精简的 App（去上帝化）
├── config/
│   └── ...
├── control/
│   └── ...
├── direct/
│   └── ...
├── infrastructure/                 # 基础设施层（新增）
│   ├── pty/
│   │   └── process.go            # PTY 进程管理
│   ├── emulator/
│   │   └── screen.go             # 终端模拟器封装
│   └── eventring/
│       └── ring.go               # 事件环形缓冲
├── logs/
│   └── ...
├── mux/
│   ├── handler.go                 # 简化为调用 SessionRouter
│   ├── session.go
│   └── virtual_socket.go          # 移除 Subprotocol()
├── protocol/
│   └── ...
├── relay/
│   ├── client_v2.go               # 精简为协调器
│   ├── http_proxy.go              # HTTP 代理（新增）
│   ├── stream_mux.go              # 流多路复用（新增）
│   └── client_v2_p2p.go           # P2P 处理器
├── relayapp/ ...
├── relaycontrol/ ...
├── relaycore/ ...
├── relaygateway/ ...
├── relaymetrics/ ...
├── relayrouter/ ...
├── relaystore/ ...
├── runtime/
│   └── supervisor.go              # 添加 RunnerRegistry
├── session/
│   ├── client.go
│   ├── manager.go
│   ├── manager_client.go
│   ├── socket.go                  # 移除 Subprotocol()
│   ├── terminal.go                # 使用 pty.Process
│   ├── screen.go                  # 类型别名 → emulator.Screen
│   └── event_ring.go              # 类型别名 → eventring.Ring
└── testutil/ ...
```

## 风险与回滚策略

每个任务独立可回滚。如果某个任务出现问题：

1. `git revert <task-commit>` 即可回滚该任务
2. 后续任务可在前一个任务的基础上继续（因为每个任务都是增量改进）
3. Task 1-6（路由去重 + V2Client 拆分 + Socket 清理）风险最低，可独立交付
4. Task 7-9（session 拆分）涉及较大范围的代码移动，但通过类型别名保持向后兼容
5. Task 10（Runner 注册）是纯增量，不影响现有行为
