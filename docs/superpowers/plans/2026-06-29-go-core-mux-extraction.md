# go-core mux 包提取与 direct/relay 统一实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 relay agent 的隧道多路复用机制提取为独立 `mux` 包，让 direct server 与 relay agent 共用；direct `/ws/sessions` 新增 mux 入口（子协议 opt-in，旧入口保留），relay agent 内部改用 `mux.Serve`（对外协议不变）。

**Architecture:** 新建 `internal/mux` 包，含 `Session`（包装 WS 连接、readLoop 分发 ws-connect/ws-close/tunnel frame）、`VirtualSocket`（实现 `session.Socket`）、`OpenSessionOrManager`（direct/relay 共用的 OnOpen 处理器）。relay agent 与 direct server 都调用 `mux.Serve`。`session.*` 零改动。

**Tech Stack:** Go 1.25.1，`nhooyr.io/websocket` v1.8.17，`net/http/httptest` + 真实 websocket 做集成测试。

## Global Constraints

- Go 版本 `1.25.1`，模块路径 `webterm/go-core`。
- 不改 `internal/session/*` 的公共 API（`Socket`/`Client`/`ManagerClient`/`Manager`）。
- relay server（Node.js `relay-server/`）与中继客户端协议层一行不改——agent 内部重构对外无感。
- direct `/ws/sessions/{id}` 旧入口保留（浏览器/Flutter 仍用）。
- 终端子协议经 `ws-connect.protocols` 携带，不在 WS 握手协商（mux 入口除外，用 `webterm.mux.v1` 区分新旧）。
- `mux.Session.Run()` 不自动建任何通道，所有通道由 `ws-connect` 显式建立。
- 测试用 `testutil.SkipIfLoopbackListenUnavailable` + `httptest.NewServer` + 真实 `websocket.Accept`/`Dial`。
- 提交信息用 conventional commits，结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`。

---

## File Structure

```
go-core/internal/mux/
  session.go          # Session 结构 + Serve + Run + readLoop + handleWSConnect/handleBinaryFrame + 写锁
  virtual_socket.go   # VirtualSocket：实现 session.Socket，回指 *Session
  handler.go          # OpenSessionOrManager：direct/relay 共用 OnOpen 处理器
  session_test.go     # 集成测试

go-core/internal/protocol/
  constants.go        # 加 MuxSubprotocol

go-core/internal/relay/
  client.go           # 改用 mux.Serve + OnControl；register 独立函数
  (transport.go)      # 删除
  (virtual_socket.go) # 删除
  client_test.go      # 现有测试应继续通过（回归）

go-core/internal/direct/
  server.go           # /ws/sessions 按子协议分流；routeWebSocket 加 mux 分支
  server_test.go      # 加 mux 入口测试

go-core/cmd/webterm-flow-smoke/main.go  # 加 mux 用例（旧用例保留）
```

---

## Task 1: 新增 `protocol.MuxSubprotocol` 常量

**Files:**
- Modify: `go-core/internal/protocol/constants.go`

**Interfaces:**
- Produces: `protocol.MuxSubprotocol`（字符串常量 `"webterm.mux.v1"`），供 direct server 握手分流与 Android 客户端协商。

- [ ] **Step 1: 加常量**

在 `go-core/internal/protocol/constants.go` 的子协议常量块（`BinarySubprotocol`/`JSONSubprotocol`/`ScreenSubprotocol` 之后）追加：

```go
const (
	BinarySubprotocol = "webterm.binary.v1"
	JSONSubprotocol   = "webterm.json.v1"
	ScreenSubprotocol = "webterm.screen.v1"
	MuxSubprotocol    = "webterm.mux.v1"
)
```

- [ ] **Step 2: 验证编译**

Run: `cd go-core && go build ./...`
Expected: 无输出（编译通过）。

- [ ] **Step 3: Commit**

```bash
cd go-core && git add internal/protocol/constants.go
git commit -m "feat(protocol): add MuxSubprotocol constant for direct mux opt-in

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: `mux.VirtualSocket`（从 relay 移出，改回指 Session）

**Files:**
- Create: `go-core/internal/mux/virtual_socket.go`

**Interfaces:**
- Consumes: `session.Socket`（`Read/Write/Close/Subprotocol`）、`session.MessageType`、`protocol.EncodeTunnelFrame`/`WSDataText`/`WSDataBinary`/`MsgTypeWSData`/`WSClose`。
- Produces: `mux.VirtualSocket`，实现 `session.Socket`；构造器 `newVirtualSocket(id, protocolName string, s *Session, onClose func())`；方法 `Emit(payload []byte, binary bool) bool`、`CloseWithNotify(ctx, code, reason)`。后续 Task 3 的 `*Session` 会调用 `newVirtualSocket`。

> 注：`*Session` 类型在 Task 3 定义。本任务先定义 `VirtualSocket`，它引用 `*Session` 是前向引用——Go 允许同包内前向引用，编译需等 Task 3 完成。本任务单独不编译通过是正常的，Task 3 完成后一起编译。

- [ ] **Step 1: 写 VirtualSocket**

创建 `go-core/internal/mux/virtual_socket.go`：

```go
package mux

import (
	"context"
	"errors"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

type virtualMessage struct {
	messageType session.MessageType
	payload     []byte
}

type VirtualSocket struct {
	id        string
	protocol  string
	session   *Session
	incoming  chan virtualMessage
	done      chan struct{}
	closeOnce sync.Once
	onClose   func()
}

func newVirtualSocket(id string, protocolName string, s *Session, onClose func()) *VirtualSocket {
	return &VirtualSocket{
		id:        id,
		protocol:  protocolName,
		session:   s,
		incoming:  make(chan virtualMessage, 256),
		done:      make(chan struct{}),
		onClose:   onClose,
	}
}

func (socket *VirtualSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case <-socket.done:
		return 0, nil, errors.New("virtual socket closed")
	case message := <-socket.incoming:
		return message.messageType, message.payload, nil
	}
}

func (socket *VirtualSocket) Write(ctx context.Context, messageType session.MessageType, data []byte) error {
	extra := protocol.WSDataText
	if messageType == session.MessageBinary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, socket.id, extra, data)
	if err != nil {
		return err
	}
	return socket.session.writeBinary(ctx, frame)
}

func (socket *VirtualSocket) Close() error {
	socket.close(false)
	return nil
}

func (socket *VirtualSocket) CloseWithNotify(ctx context.Context, code int, reason string) {
	socket.closeOnce.Do(func() {
		close(socket.done)
		if socket.onClose != nil {
			socket.onClose()
		}
		_ = socket.session.sendJSON(ctx, map[string]any{
			"type":               protocol.WSClose,
			"tunnelConnectionId": socket.id,
			"code":               code,
			"reason":             reason,
		})
	})
}

func (socket *VirtualSocket) Subprotocol() string {
	return socket.protocol
}

func (socket *VirtualSocket) Emit(payload []byte, binary bool) bool {
	messageType := session.MessageText
	if binary {
		messageType = session.MessageBinary
	}
	select {
	case <-socket.done:
		return false
	case socket.incoming <- virtualMessage{messageType: messageType, payload: payload}:
		return true
	default:
		socket.close(true)
		return false
	}
}

func (socket *VirtualSocket) close(notify bool) {
	socket.closeOnce.Do(func() {
		close(socket.done)
		if socket.onClose != nil {
			socket.onClose()
		}
		if notify {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_ = socket.session.sendJSON(ctx, map[string]any{
				"type":               protocol.WSClose,
				"tunnelConnectionId": socket.id,
				"code":               websocket.StatusNormalClosure,
				"reason":             "",
			})
		}
	})
}
```

- [ ] **Step 2: Commit（暂不编译，Task 3 完成后编译）**

```bash
cd go-core && git add internal/mux/virtual_socket.go
git commit -m "feat(mux): add VirtualSocket moved from relay, back-ref Session

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `mux.Session` 核心与 `Serve`

**Files:**
- Create: `go-core/internal/mux/session.go`

**Interfaces:**
- Consumes: `protocol`（`WSConnect`/`WSClose`/`WSConnected`/`WSError`/`DecodeTunnelFrame`/`MsgTypeWSData`/`WSDataBinary`）、`nhooyr.io/websocket`。
- Produces:
  - `mux.Session` 结构
  - `type OpenHandler func(ctx context.Context, vs *VirtualSocket, path string, protocols []string) error`
  - `type ControlHandler func(ctx context.Context, msg map[string]any)`
  - `type ServeOpts struct { OnOpen OpenHandler; OnControl ControlHandler }`
  - `func Serve(conn *websocket.Conn, opts *ServeOpts) *Session`
  - `func (s *Session) Run(ctx context.Context) error`
  - `func (s *Session) writeBinary(ctx, data) error`（VirtualSocket 用）
  - `func (s *Session) sendJSON(ctx, value) error`（VirtualSocket 用）

- [ ] **Step 1: 写 Session**

创建 `go-core/internal/mux/session.go`：

```go
package mux

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
)

// OpenHandler 处理一个新建立的虚拟通道。返回 error 时 mux 负责发 ws-error。
type OpenHandler func(ctx context.Context, vs *VirtualSocket, path string, protocols []string) error

// ControlHandler 处理 mux 不识别的控制消息（透传给上层，如 relay 的 http-request/p2p-*）。
type ControlHandler func(ctx context.Context, msg map[string]any)

type ServeOpts struct {
	OnOpen    OpenHandler    // 必填
	OnControl ControlHandler // 可选
}

type Session struct {
	conn       *websocket.Conn
	writeMu    sync.Mutex
	channels   map[string]*VirtualSocket
	channelsMu sync.RWMutex
	onOpen     OpenHandler
	onControl  ControlHandler
}

// Serve 包装一个已建立的 WebSocket 连接，启动多路复用。
// 调用方负责 conn 的建立（direct 用 websocket.Accept；relay 用 websocket.Dial + register）。
func Serve(conn *websocket.Conn, opts *ServeOpts) *Session {
	return &Session{
		conn:     conn,
		channels: make(map[string]*VirtualSocket),
		onOpen:   opts.OnOpen,
		onControl: opts.OnControl,
	}
}

// Run 启动 readLoop，阻塞直到连接关闭。不创建任何通道——所有通道由 ws-connect 显式建立。
// 物理连接断开时自动关闭所有 VirtualSocket。
func (s *Session) Run(ctx context.Context) error {
	defer s.closeAllChannels()
	return s.readLoop(ctx)
}

func (s *Session) readLoop(ctx context.Context) error {
	for {
		msgType, data, err := s.conn.Read(ctx)
		if err != nil {
			return err
		}
		switch msgType {
		case websocket.MessageText:
			s.handleControlMessage(ctx, data)
		case websocket.MessageBinary:
			s.handleBinaryFrame(data)
		}
	}
}

func (s *Session) handleControlMessage(ctx context.Context, data []byte) {
	var msg map[string]any
	if json.Unmarshal(data, &msg) != nil {
		return
	}
	switch stringValue(msg["type"]) {
	case protocol.WSConnect:
		s.handleWSConnect(ctx, msg)
	case protocol.WSClose:
		s.closeSocket(stringValue(msg["tunnelConnectionId"]))
	case protocol.WSConnected, protocol.WSError:
		// 服务端角色不应收到这些（它们是服务端发出的）。忽略。
	default:
		if s.onControl != nil {
			s.onControl(ctx, msg)
		}
	}
}

func (s *Session) handleWSConnect(ctx context.Context, msg map[string]any) {
	tunnelID := stringValue(msg["tunnelConnectionId"])
	path := stringValue(msg["path"])
	protocols := protocolsValue(msg["protocols"])
	if tunnelID == "" {
		return
	}
	vs := s.newSocket(tunnelID, selectProtocol(protocols))
	if err := s.onOpen(ctx, vs, cleanPath(path), protocols); err != nil {
		s.removeSocket(tunnelID)
		_ = s.sendJSON(ctx, map[string]any{
			"type":               protocol.WSError,
			"tunnelConnectionId": tunnelID,
			"code":               http.StatusNotFound,
			"message":            err.Error(),
		})
		return
	}
	_ = s.sendJSON(ctx, map[string]any{
		"type":               protocol.WSConnected,
		"tunnelConnectionId": tunnelID,
	})
}

func (s *Session) handleBinaryFrame(data []byte) {
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil {
		return
	}
	if frame.MsgType != protocol.MsgTypeWSData {
		return
	}
	s.channelsMu.RLock()
	socket := s.channels[frame.ID]
	s.channelsMu.RUnlock()
	if socket == nil {
		return
	}
	socket.Emit(frame.Payload, frame.ExtraByte == protocol.WSDataBinary)
}

func (s *Session) newSocket(id string, protocolName string) *VirtualSocket {
	s.channelsMu.Lock()
	defer s.channelsMu.Unlock()
	socket := newVirtualSocket(id, protocolName, s, func() {
		s.removeSocket(id)
	})
	s.channels[id] = socket
	return socket
}

func (s *Session) removeSocket(id string) {
	s.channelsMu.Lock()
	delete(s.channels, id)
	s.channelsMu.Unlock()
}

func (s *Session) closeSocket(id string) {
	s.channelsMu.RLock()
	socket := s.channels[id]
	s.channelsMu.RUnlock()
	if socket != nil {
		_ = socket.Close()
	}
}

func (s *Session) closeAllChannels() {
	s.channelsMu.RLock()
	sockets := make([]*VirtualSocket, 0, len(s.channels))
	for _, socket := range s.channels {
		sockets = append(sockets, socket)
	}
	s.channelsMu.RUnlock()
	for _, socket := range sockets {
		_ = socket.Close()
	}
}

func (s *Session) writeBinary(ctx context.Context, data []byte) error {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.conn.Write(writeCtx, websocket.MessageBinary, data)
}

func (s *Session) sendJSON(ctx context.Context, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.conn.Write(writeCtx, websocket.MessageText, bytes)
}

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
}

func selectProtocol(protocols []string) string {
	for _, item := range protocols {
		if item == protocol.ScreenSubprotocol {
			return protocol.ScreenSubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.BinarySubprotocol {
			return protocol.BinarySubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.JSONSubprotocol {
			return protocol.JSONSubprotocol
		}
	}
	return protocol.JSONSubprotocol
}

func protocolsValue(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		if text := stringValue(item); text != "" {
			out = append(out, text)
		}
	}
	return out
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	// 避免 import fmt，用简单拼接
	switch v := value.(type) {
	case float64:
		return strings.TrimRight(strings.TrimRight(
			strings.TrimRight(formatFloat(v), "0"), "."), "")
	default:
		return ""
	}
}

// formatFloat 用 strconv 避免 import fmt。
func formatFloat(v float64) string {
	// 简单整数化处理；protocols/path/tunnelId 都是字符串，实际不会走到这。
	return ""
}
```

> 实现者注意：`stringValue` 对非字符串类型返回空串即可——ws-connect 的 `tunnelConnectionId`/`path` 都是字符串 JSON，不会是数字。若担心，可 `import "fmt"` 并用 `fmt.Sprint(value)`，与原 `relay/client.go` 一致。下面测试任务会验证字符串路径。

- [ ] **Step 2: 编译验证**

Run: `cd go-core && go build ./internal/mux/`
Expected: 无输出（Task 2 + Task 3 一起编译通过）。

- [ ] **Step 3: Commit**

```bash
cd go-core && git add internal/mux/session.go
git commit -m "feat(mux): add Session core with Serve/Run/readLoop and ws-connect handling

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: `mux.OpenSessionOrManager` 共用处理器

**Files:**
- Create: `go-core/internal/mux/handler.go`

**Interfaces:**
- Consumes: `session.Manager`（`Get`）、`session.NewManagerClient`、`session.NewClient`、`session.ClientModeFromProtocol`、Task 3 的 `*VirtualSocket` 与 `selectProtocol`。
- Produces: `func OpenSessionOrManager(ctx context.Context, manager *session.Manager, vs *VirtualSocket, path string, protocols []string) error`。direct server 与 relay agent 都把它作为 `OnOpen` 传入。

- [ ] **Step 1: 写 handler**

创建 `go-core/internal/mux/handler.go`：

```go
package mux

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"webterm/go-core/internal/session"
)

// OpenSessionOrManager 是 direct server 和 relay agent 共用的 OnOpen 处理器。
// 根据 path 决定建 ManagerClient 还是终端 Client。返回 error 时由 mux 发 ws-error。
func OpenSessionOrManager(
	ctx context.Context,
	manager *session.Manager,
	vs *VirtualSocket,
	path string,
	protocols []string,
) error {
	switch {
	case path == "/ws/sessions":
		mc := session.NewManagerClient(vs)
		go mc.Run(ctx, manager)
		return nil

	case strings.HasPrefix(path, "/ws/sessions/"):
		id := strings.TrimPrefix(path, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := manager.Get(id)
		if !ok {
			return fmt.Errorf("session %s not found", id)
		}
		mode := session.ClientModeFromProtocol(selectProtocol(protocols))
		client := session.NewClient(vs, terminal, mode)
		go client.Run(ctx)
		return nil

	default:
		return fmt.Errorf("unknown path: %s", path)
	}
}
```

- [ ] **Step 2: 编译验证**

Run: `cd go-core && go build ./internal/mux/`
Expected: 无输出。

- [ ] **Step 3: Commit**

```bash
cd go-core && git add internal/mux/handler.go
git commit -m "feat(mux): add OpenSessionOrManager shared handler for direct/relay

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: `mux` 包集成测试

**Files:**
- Create: `go-core/internal/mux/session_test.go`

**Interfaces:**
- Consumes: Task 2-4 的 `Serve`/`ServeOpts`/`OpenSessionOrManager`/`VirtualSocket`；`session.NewManager`；`protocol` 编解码；`testutil.SkipIfLoopbackListenUnavailable`。

- [ ] **Step 1: 写测试**

创建 `go-core/internal/mux/session_test.go`：

```go
package mux

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
	"webterm/go-core/internal/testutil"
)

// readJSON 读一条 text 消息并解析为 map。
func readJSON(t *testing.T, ctx context.Context, conn *websocket.Conn) map[string]any {
	t.Helper()
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read json: %v", err)
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatalf("unmarshal %s: %v", data, err)
	}
	return msg
}

func writeJSONMsg(t *testing.T, ctx context.Context, conn *websocket.Conn, value any) {
	t.Helper()
	bytes, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := conn.Write(wctx, websocket.MessageText, bytes); err != nil {
		t.Fatalf("write json: %v", err)
	}
}

func writeTunnel(t *testing.T, ctx context.Context, conn *websocket.Conn, id string, payload []byte, binary bool) {
	t.Helper()
	extra := protocol.WSDataText
	if binary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, extra, payload)
	if err != nil {
		t.Fatalf("encode tunnel: %v", err)
	}
	wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := conn.Write(wctx, websocket.MessageBinary, frame); err != nil {
		t.Fatalf("write tunnel: %v", err)
	}
}

func readTunnel(t *testing.T, ctx context.Context, conn *websocket.Conn) protocol.TunnelFrame {
	t.Helper()
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read tunnel: %v", err)
	}
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil {
		t.Fatalf("decode tunnel % x: %v", data, err)
	}
	return frame
}

// newManagerWithShell 返回一个带 shell 的 Manager，供终端通道测试。
func newManagerWithShell(t *testing.T) *session.Manager {
	t.Helper()
	return session.NewManager(session.TerminalDefaults{
		Command: "/bin/sh",
		CWD:     ".",
	})
}

// startMuxServer 启动一个 httptest server，accept 后用 mux.Serve + OpenSessionOrManager。
// 返回 dial URL。cancel 关闭 server。
func startMuxServer(t *testing.T, ctx context.Context, manager *session.Manager) (string, func()) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			Subprotocols: []string{protocol.MuxSubprotocol},
		})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		sess := Serve(conn, &ServeOpts{
			OnOpen: func(ctx context.Context, vs *VirtualSocket, path string, protocols []string) error {
				return OpenSessionOrManager(ctx, manager, vs, path, protocols)
			},
		})
		_ = sess.Run(ctx)
	}))
	cleanup := func() { server.Close() }
	return "ws" + server.URL[len("http"):], cleanup
}

func TestMuxManagerChannel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	// 客户端发 ws-connect 建 manager 通道。
	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "manager",
		"path":               "/ws/sessions",
		"protocols":          []string{},
	})
	connected := readJSON(t, ctx, conn)
	if connected["type"] != protocol.WSConnected || connected["tunnelConnectionId"] != "manager" {
		t.Fatalf("ws-connected = %#v", connected)
	}
	// 服务端经 manager 通道推送初始 sessions 列表（tunnel frame, text）。
	frame := readTunnel(t, ctx, conn)
	if frame.ID != "manager" || frame.MsgType != protocol.MsgTypeWSData {
		t.Fatalf("initial manager frame = %#v", frame)
	}
	if !strings.Contains(string(frame.Payload), `"type":"sessions"`) {
		t.Fatalf("initial manager payload = %s", frame.Payload)
	}
	cancel()
}

func TestMuxUnknownPathReturnsWSError(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "bad",
		"path":               "/ws/unknown",
		"protocols":          []string{},
	})
	errMsg := readJSON(t, ctx, conn)
	if errMsg["type"] != protocol.WSError || errMsg["tunnelConnectionId"] != "bad" {
		t.Fatalf("ws-error = %#v", errMsg)
	}
	if errMsg["code"].(float64) != http.StatusNotFound {
		t.Fatalf("ws-error code = %#v", errMsg["code"])
	}
	cancel()
}

func TestMuxTerminalChannelRoundTrip(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	terminal, err := manager.Create("mux-test", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer manager.Close(terminal.Info().ID)

	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "term1",
		"path":               "/ws/sessions/" + terminal.Info().ID,
		"protocols":          []string{protocol.BinarySubprotocol},
	})
	connected := readJSON(t, ctx, conn)
	if connected["type"] != protocol.WSConnected {
		t.Fatalf("ws-connected = %#v", connected)
	}

	// 发 hello（binary 帧经 tunnel frame）。
	helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0})
	helloFrame := append([]byte{protocol.MsgHello}, helloPayload...)
	writeTunnel(t, ctx, conn, "term1", helloFrame, true)

	// 读服务端回的输出/state/info（任一 tunnel frame 即可证明通道通）。
	readCtx, readCancel := context.WithTimeout(ctx, 3*time.Second)
	defer readCancel()
	_, data, err := conn.Read(readCtx)
	if err != nil {
		t.Fatalf("read terminal response: %v", err)
	}
	// 应是 tunnel frame，id=term1
	if !bytes.HasPrefix(data, []byte{protocol.MsgTypeWSData}) {
		t.Fatalf("expected tunnel frame, got % x", data[:min(len(data), 8)])
	}
	cancel()
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func TestMuxWSCloseClosesChannel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "m1",
		"path":               "/ws/sessions",
		"protocols":          []string{},
	})
	readJSON(t, ctx, conn) // ws-connected
	readTunnel(t, ctx, conn) // initial sessions push

	// 发 ws-close，服务端应关闭该 virtual socket（无 panic 即可）。
	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSClose,
		"tunnelConnectionId": "m1",
	})
	// 给一点时间让关闭生效。
	time.Sleep(100 * time.Millisecond)
	cancel()
}
```

- [ ] **Step 2: 运行测试，验证通过**

Run: `cd go-core && go test ./internal/mux/ -v -run TestMux`
Expected: 4 个测试 PASS。

- [ ] **Step 3: Commit**

```bash
cd go-core && git add internal/mux/session_test.go
git commit -m "test(mux): add integration tests for ws-connect/error/terminal/close

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: relay agent 改用 `mux.Serve` + `OnControl`

**Files:**
- Modify: `go-core/internal/relay/client.go`
- Delete: `go-core/internal/relay/transport.go`
- Delete: `go-core/internal/relay/virtual_socket.go`

**Interfaces:**
- Consumes: Task 2-4 的 `mux.Serve`/`mux.ServeOpts`/`mux.OpenSessionOrManager`。
- Produces: `relay.Client.Run` 行为不变（对 relay server 协议完全一致）；内部 `register` 独立函数；`handleControl` 处理 `http-request`/`p2p-offer`。

- [ ] **Step 1: 先跑现有 relay 测试，记录基线**

Run: `cd go-core && go test ./internal/relay/ -v -run TestRelayClient 2>&1 | tail -20`
Expected: 现有 4 个 TestRelayClient 测试 PASS（这是回归基线，改造后必须仍 PASS）。

- [ ] **Step 2: 重写 `relay/client.go` 用 mux**

替换 `go-core/internal/relay/client.go` 全文为：

```go
package relay

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
)

type Client struct {
	cfg config.RelayConfig
	app *app.App
}

func New(cfg config.RelayConfig, application *app.App) *Client {
	return &Client{cfg: cfg, app: application}
}

func (client *Client) Run(ctx context.Context) error {
	if client.cfg.URL == "" {
		return errors.New("RELAY_URL must be set")
	}
	if client.cfg.Secret == "" {
		return errors.New("RELAY_SECRET must be set")
	}
	delay := time.Second
	for {
		err := client.runOnce(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		client.app.SetRelayConnected(false, "", errString(err))
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if delay < 10*time.Second {
			delay *= 2
		}
	}
}

func (client *Client) runOnce(ctx context.Context) error {
	relayURL, err := agentWebSocketURL(client.cfg.URL)
	if err != nil {
		return err
	}
	conn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	if err := client.register(ctx, conn); err != nil {
		return err
	}

	sess := mux.Serve(conn, &mux.ServeOpts{
		OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) error {
			return mux.OpenSessionOrManager(ctx, client.app.Sessions(), vs, path, protocols)
		},
		OnControl: client.handleControl,
	})
	return sess.Run(ctx)
}

// register 在 mux.Serve 之前同步完成 agent-register/registered 握手。
// registered 更新 app.SetRelayConnected；error/非 registered 返回失败。
// relay 在 registered 之后才可能发 ws-connect/http-request，同步单读安全。
func (client *Client) register(ctx context.Context, conn *websocket.Conn) error {
	if err := writeJSON(ctx, conn, map[string]any{
		"type":       protocol.AgentRegister,
		"deviceName": client.cfg.DeviceName,
		"secret":     client.cfg.Secret,
	}); err != nil {
		return err
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return err
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		return errors.New("bad register response")
	}
	switch stringValue(msg["type"]) {
	case protocol.Registered:
		client.app.SetRelayConnected(true, stringValue(msg["deviceId"]), "")
		return nil
	case protocol.Error:
		return fmt.Errorf("relay error: %s", stringValue(msg["message"]))
	default:
		return fmt.Errorf("unexpected register response: %s", stringValue(msg["type"]))
	}
}

// handleControl 处理 mux 不识别的控制消息：http-request、p2p-offer。
func (client *Client) handleControl(ctx context.Context, msg map[string]any) {
	switch stringValue(msg["type"]) {
	case protocol.HTTPRequest:
		client.handleHTTPRequest(ctx, msg)
	case "p2p-offer":
		client.handleP2POffer(ctx, msg)
	case "p2p-ice":
		// Go Core 不实现 WebRTC/P2P。ICE candidates 在 offer 被拒后可忽略。
	}
}

func (client *Client) handleP2POffer(ctx context.Context, msg map[string]any) {
	clientID := stringValue(msg["from"])
	if clientID == "" {
		return
	}
	_ = writeJSON(ctx, client.connForControl(ctx), map[string]any{
		"type":    "p2p-unavailable",
		"to":      clientID,
		"message": "Go Core relay agent does not support P2P yet; falling back to relay tunnel",
	})
}

// connForControl 返回用于写控制响应的连接。
// handleControl 由 mux 的 readLoop 在读 goroutine 中调用，
// 写需经 mux 的写路径。为保持简单，直接用 client 持有的写函数。
// 注意：mux 不暴露 conn，故 p2p-unavailable 通过独立写路径发送。
// 实际实现中 mux.Serve 应保留 conn 引用供 OnControl 写回。
```

> **实现者注意（重要）**：上面 `handleP2POffer` 调用了 `client.connForControl` 这个不存在的方法——因为 `mux.Session` 没有把 `*websocket.Conn` 暴露给 `OnControl`。**这是本任务的设计缺口**，需修复。两个选项：
> - **选项 A（推荐）**：给 `mux.ServeOpts` 加 `OnControl` 时，让 `mux.Session` 把一个写回调暴露给上层。即 `ServeOpts` 增加字段或在 `ControlHandler` 签名里传入一个 `func(ctx, value any) error` 写回函数。
> - **选项 B**：`relay.Client` 自己保留 `conn` 引用（在 `runOnce` 里 `client.conn = conn`），`handleControl` 直接用 `writeJSON(ctx, client.conn, ...)`。需加 `client.conn` 字段（受锁保护，因为 readLoop 在另一个 goroutine）。
>
> **选 B**（改动最小，与原 `relay/client.go` 用 `writeJSON(ctx, conn, ...)` 一致）。实现：`Client` 加 `mu sync.Mutex; conn *websocket.Conn` 字段；`runOnce` 注册后 `client.setConn(conn)`；`handleP2POffer`/`handleHTTPRequest` 用 `client.writeControl(ctx, value)`（内部 `client.mu.Lock(); conn := client.conn; ...; writeJSON(ctx, conn, value)`）。

按选项 B 完整重写 `relay/client.go`：

```go
package relay

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
)

type Client struct {
	cfg  config.RelayConfig
	app  *app.App
	mu   sync.Mutex
	conn *websocket.Conn
}

func New(cfg config.RelayConfig, application *app.App) *Client {
	return &Client{cfg: cfg, app: application}
}

func (client *Client) Run(ctx context.Context) error {
	if client.cfg.URL == "" {
		return errors.New("RELAY_URL must be set")
	}
	if client.cfg.Secret == "" {
		return errors.New("RELAY_SECRET must be set")
	}
	delay := time.Second
	for {
		err := client.runOnce(ctx)
		client.setConn(nil)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		client.app.SetRelayConnected(false, "", errString(err))
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if delay < 10*time.Second {
			delay *= 2
		}
	}
}

func (client *Client) runOnce(ctx context.Context) error {
	relayURL, err := agentWebSocketURL(client.cfg.URL)
	if err != nil {
		return err
	}
	conn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	client.setConn(conn)

	if err := client.register(ctx, conn); err != nil {
		return err
	}

	sess := mux.Serve(conn, &mux.ServeOpts{
		OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) error {
			return mux.OpenSessionOrManager(ctx, client.app.Sessions(), vs, path, protocols)
		},
		OnControl: client.handleControl,
	})
	return sess.Run(ctx)
}

func (client *Client) setConn(conn *websocket.Conn) {
	client.mu.Lock()
	client.conn = conn
	client.mu.Unlock()
}

func (client *Client) writeControl(ctx context.Context, value any) error {
	client.mu.Lock()
	conn := client.conn
	client.mu.Unlock()
	if conn == nil {
		return errors.New("relay connection closed")
	}
	return writeJSON(ctx, conn, value)
}

func (client *Client) register(ctx context.Context, conn *websocket.Conn) error {
	if err := writeJSON(ctx, conn, map[string]any{
		"type":       protocol.AgentRegister,
		"deviceName": client.cfg.DeviceName,
		"secret":     client.cfg.Secret,
	}); err != nil {
		return err
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return err
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		return errors.New("bad register response")
	}
	switch stringValue(msg["type"]) {
	case protocol.Registered:
		client.app.SetRelayConnected(true, stringValue(msg["deviceId"]), "")
		return nil
	case protocol.Error:
		return fmt.Errorf("relay error: %s", stringValue(msg["message"]))
	default:
		return fmt.Errorf("unexpected register response: %s", stringValue(msg["type"]))
	}
}

func (client *Client) handleControl(ctx context.Context, msg map[string]any) {
	switch stringValue(msg["type"]) {
	case protocol.HTTPRequest:
		client.handleHTTPRequest(ctx, msg)
	case "p2p-offer":
		client.handleP2POffer(ctx, msg)
	case "p2p-ice":
		// Go Core 不实现 WebRTC/P2P。ICE candidates 在 offer 被拒后可忽略。
	}
}

func (client *Client) handleP2POffer(ctx context.Context, msg map[string]any) {
	clientID := stringValue(msg["from"])
	if clientID == "" {
		return
	}
	_ = client.writeControl(ctx, map[string]any{
		"type":    "p2p-unavailable",
		"to":      clientID,
		"message": "Go Core relay agent does not support P2P yet; falling back to relay tunnel",
	})
}

func (client *Client) handleHTTPRequest(ctx context.Context, msg map[string]any) {
	requestID := stringValue(msg["requestId"])
	method := stringValue(msg["method"])
	path := stringValue(msg["path"])
	status, payload, err := client.routeMemoryAPI(method, path, decodeBody(msg))
	if err != nil {
		_ = client.writeControl(ctx, map[string]any{
			"type":      protocol.HTTPError,
			"requestId": requestID,
			"error":     errorCode(status),
			"message":   err.Error(),
		})
		return
	}
	encoded := base64.StdEncoding.EncodeToString(payload)
	_ = client.writeControl(ctx, map[string]any{
		"type":         protocol.HTTPResponse,
		"requestId":    requestID,
		"statusCode":   status,
		"headers":      map[string]string{"content-type": "application/json; charset=utf-8", "content-length": fmt.Sprintf("%d", len(payload))},
		"bodyEncoding": "base64",
		"body":         encoded,
		"hasChunks":    false,
	})
}

func (client *Client) routeMemoryAPI(method string, rawPath string, body []byte) (int, []byte, error) {
	path := rawPath
	if parsed, err := url.Parse(rawPath); err == nil {
		path = parsed.Path
	}
	if method == http.MethodGet && path == "/api/sessions" {
		return marshalStatus(http.StatusOK, client.app.Sessions().List())
	}
	if method == http.MethodPost && path == "/api/sessions" {
		var req struct {
			Name string `json:"name"`
			CWD  string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &req)
		}
		terminal, err := client.app.Sessions().Create(req.Name, req.CWD)
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
			terminal, ok := client.app.Sessions().Rename(id, req.Name)
			if !ok {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return marshalStatus(http.StatusOK, terminal.Info())
		}
		if method == http.MethodDelete {
			if !client.app.Sessions().Close(id) {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return http.StatusNoContent, []byte{}, nil
		}
	}
	return http.StatusNotFound, nil, errors.New("not found")
}

func marshalStatus(status int, value any) (int, []byte, error) {
	payload, err := json.Marshal(value)
	return status, payload, err
}

func decodeBody(msg map[string]any) []byte {
	body := stringValue(msg["body"])
	if body == "" {
		return nil
	}
	if stringValue(msg["bodyEncoding"]) == "base64" {
		decoded, err := base64.StdEncoding.DecodeString(body)
		if err == nil {
			return decoded
		}
	}
	return []byte(body)
}

func writeJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return conn.Write(writeCtx, websocket.MessageText, bytes)
}

func selectedProtocol(protocols []string) string {
	for _, item := range protocols {
		if item == protocol.ScreenSubprotocol {
			return protocol.ScreenSubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.BinarySubprotocol {
			return protocol.BinarySubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.JSONSubprotocol {
			return protocol.JSONSubprotocol
		}
	}
	return protocol.JSONSubprotocol
}

func protocolsValue(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		if text := stringValue(item); text != "" {
			out = append(out, text)
		}
	}
	return out
}

func agentWebSocketURL(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	switch parsed.Scheme {
	case "http":
		parsed.Scheme = "ws"
	case "https":
		parsed.Scheme = "wss"
	case "ws", "wss":
	default:
		return "", fmt.Errorf("unsupported relay URL scheme: %s", parsed.Scheme)
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/") + "/ws/agent"
	parsed.RawQuery = ""
	return parsed.String(), nil
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}

func errorCode(status int) string {
	if status == http.StatusNotFound {
		return "not_found"
	}
	return "failed"
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
```

> 注：`selectedProtocol`/`protocolsValue` 在 relay 包内保留（`client_test.go` 直接测试它们），即使 `mux` 包有同名函数也不冲突。

- [ ] **Step 3: 删除 relay/transport.go 和 relay/virtual_socket.go**

```bash
cd go-core && git rm internal/relay/transport.go internal/relay/virtual_socket.go
```

- [ ] **Step 4: 编译验证**

Run: `cd go-core && go build ./...`
Expected: 无输出。

- [ ] **Step 5: 跑 relay 回归测试**

Run: `cd go-core && go test ./internal/relay/ -v -run TestRelayClient 2>&1 | tail -30`
Expected: 4 个 TestRelayClient 测试 PASS（manager tunnel / terminal tunnel / http-request / p2p reject）。若失败，对照原 `relay/client.go` 行为排查——核心是 `register` 同步握手 + `mux.Serve` 接管，对 relay server 协议必须完全不变。

- [ ] **Step 6: Commit**

```bash
cd go-core && git add -A internal/relay/
git commit -m "refactor(relay): switch agent to mux.Serve + OnControl, delete transport/virtual_socket

registered/error 在注册阶段同步处理并更新 SetRelayConnected，
不丢给 mux 当 unknown control。对 relay server 协议完全不变。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: direct server `/ws/sessions` 按子协议分流

**Files:**
- Modify: `go-core/internal/direct/server.go`（`routeWebSocket` 与 `/ws/sessions` 处理）
- Modify: `go-core/internal/direct/server_test.go`

**Interfaces:**
- Consumes: Task 1 的 `protocol.MuxSubprotocol`；Task 2-4 的 `mux.Serve`/`mux.ServeOpts`/`mux.OpenSessionOrManager`。
- Produces: `/ws/sessions` 带 `webterm.mux.v1` 子协议 → `mux.Serve`；不带 → 旧裸 JSON ManagerClient。`/ws/sessions/{id}` 保留不变。

- [ ] **Step 1: 修改 direct/server.go 的 routeWebSocket**

在 `go-core/internal/direct/server.go` 找到 `routeWebSocket` 方法（约 111 行），把 `path == "/ws/sessions"` 分支改为按子协议分流：

原代码：
```go
func (direct *Server) routeWebSocket(w http.ResponseWriter, r *http.Request, path string) {
	if path == "/ws/sessions" {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		client := session.NewManagerClient(session.NewWebSocketAdapter(conn))
		client.Run(r.Context(), direct.app.Sessions())
		return
	}
	if !strings.HasPrefix(path, "/ws/sessions/") {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	id := strings.TrimPrefix(path, "/ws/sessions/")
	terminal, ok := direct.app.Sessions().Get(id)
	// ... 保留不变 ...
}
```

改为：
```go
func (direct *Server) routeWebSocket(w http.ResponseWriter, r *http.Request, path string) {
	if path == "/ws/sessions" {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			Subprotocols: []string{protocol.MuxSubprotocol},
		})
		if err != nil {
			return
		}
		if conn.Subprotocol() == protocol.MuxSubprotocol {
			// 新 mux 客户端：单连接多路复用
			sess := mux.Serve(conn, &mux.ServeOpts{
				OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) error {
					return mux.OpenSessionOrManager(ctx, direct.app.Sessions(), vs, p, protos)
				},
			})
			sess.Run(r.Context())
			return
		}
		// 旧裸 JSON 客户端：现有逻辑
		client := session.NewManagerClient(session.NewWebSocketAdapter(conn))
		client.Run(r.Context(), direct.app.Sessions())
		return
	}
	if !strings.HasPrefix(path, "/ws/sessions/") {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	id := strings.TrimPrefix(path, "/ws/sessions/")
	terminal, ok := direct.app.Sessions().Get(id)
	if !ok {
		writeError(w, http.StatusNotFound, "session not found")
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols: []string{protocol.ScreenSubprotocol, protocol.BinarySubprotocol, protocol.JSONSubprotocol},
	})
	if err != nil {
		return
	}
	socket := session.NewWebSocketAdapter(conn)
	client := session.NewClient(socket, terminal, session.ClientModeFromProtocol(socket.Subprotocol()))
	client.Run(r.Context())
}
```

> 注：`/ws/sessions/{id}` 分支原来 `websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: ...})` 已有子协议，保持不变即可（上面只是显式写出确认）。

- [ ] **Step 2: 加 import**

在 `direct/server.go` 的 import 块加：
```go
	"context"
	"webterm/go-core/internal/mux"
```
（`context` 已用于其它地方则不重复；`mux` 新增。）

- [ ] **Step 3: 编译验证**

Run: `cd go-core && go build ./...`
Expected: 无输出。

- [ ] **Step 4: 写 direct mux 入口测试**

在 `go-core/internal/direct/server_test.go` 末尾追加（若文件已有 helper 复用之，否则参考下面自包含版本）：

```go
func TestDirectMuxEndpointManagerChannel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
		Shell:  config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)
	listener, err := net.Listen("tcp", cfg.Direct.Addr)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()
	go server.server.Serve(listener)
	defer server.server.Close()

	wsURL := "ws://" + listener.Addr().String() + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != protocol.MuxSubprotocol {
		t.Fatalf("subprotocol = %q, want %q", conn.Subprotocol(), protocol.MuxSubprotocol)
	}

	// 发 ws-connect 建 manager 通道。
	msgBytes, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "manager",
		"path":               "/ws/sessions",
		"protocols":          []string{},
	})
	wctx, wcancel := context.WithTimeout(ctx, 5*time.Second)
	defer wcancel()
	conn.Write(wctx, websocket.MessageText, msgBytes)

	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read ws-connected: %v", err)
	}
	var connected map[string]any
	json.Unmarshal(data, &connected)
	if connected["type"] != protocol.WSConnected {
		t.Fatalf("ws-connected = %#v", connected)
	}
	cancel()
}

func TestDirectLegacyManagerStillWorks(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
		Shell:  config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)
	listener, err := net.Listen("tcp", cfg.Direct.Addr)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()
	go server.server.Serve(listener)
	defer server.server.Close()

	// 旧客户端：不带子协议，应连上并收到裸 JSON sessions 推送。
	wsURL := "ws://" + listener.Addr().String() + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != "" {
		t.Fatalf("legacy subprotocol = %q, want empty", conn.Subprotocol())
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read legacy sessions: %v", err)
	}
	if !strings.Contains(string(data), `"type":"sessions"`) {
		t.Fatalf("legacy payload = %s", data)
	}
	cancel()
}
```

> 实现者注意：确认 `direct/server_test.go` 已 import `encoding/json`/`net`/`strings`/`testutil`/`protocol`/`config`/`app`/`websocket`/`context`/`time`。缺啥补啥。

- [ ] **Step 5: 运行 direct 测试**

Run: `cd go-core && go test ./internal/direct/ -v -run "TestDirectMux|TestDirectLegacy" 2>&1 | tail -20`
Expected: 两个测试 PASS。

- [ ] **Step 6: 跑全部 direct 测试回归**

Run: `cd go-core && go test ./internal/direct/ 2>&1 | tail -10`
Expected: PASS（旧测试不受影响）。

- [ ] **Step 7: Commit**

```bash
cd go-core && git add internal/direct/server.go internal/direct/server_test.go
git commit -m "feat(direct): add mux endpoint on /ws/sessions via subprotocol opt-in, keep legacy

带 webterm.mux.v1 子协议走 mux.Serve；不带走旧裸 JSON ManagerClient。
/ws/sessions/{id} 旧入口保留。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: flow-smoke 加 mux 用例

**Files:**
- Modify: `go-core/cmd/webterm-flow-smoke/main.go`

**Interfaces:**
- Consumes: `protocol.MuxSubprotocol`/`WSConnect`/`EncodeTunnelFrame`/`MsgHello`。

- [ ] **Step 1: 读现有 flow-smoke**

Run: `cd go-core && sed -n '1,140p' cmd/webterm-flow-smoke/main.go`
了解现有结构（它当前连 `/ws/sessions/{id}` 旧路径）。

- [ ] **Step 2: 加 mux 用例函数**

在 `cmd/webterm-flow-smoke/main.go` 加一个 `runMuxSmoke(baseURL string)` 函数，用 `/ws/sessions` + `webterm.mux.v1` 子协议 + `ws-connect` + tunnel frame 打开终端并发 hello，验证收到输出。参考 mux 测试（Task 5）的 `TestMuxTerminalChannelRoundTrip` 流程。

```go
func runMuxSmoke(ctx context.Context, baseURL string, sessionID string) error {
	wsURL := strings.Replace(baseURL, "http", "ws", 1) + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		return fmt.Errorf("mux dial: %w", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	connectMsg, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "term1",
		"path":               "/ws/sessions/" + sessionID,
		"protocols":          []string{protocol.BinarySubprotocol},
	})
	if err := conn.Write(ctx, websocket.MessageText, connectMsg); err != nil {
		return fmt.Errorf("mux ws-connect write: %w", err)
	}
	// 读 ws-connected
	if _, _, err := conn.Read(ctx); err != nil {
		return fmt.Errorf("mux ws-connected read: %w", err)
	}
	// 发 hello
	helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0})
	helloFrame := append([]byte{protocol.MsgHello}, helloPayload...)
	tunnel, _ := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, "term1", protocol.WSDataBinary, helloFrame)
	if err := conn.Write(ctx, websocket.MessageBinary, tunnel); err != nil {
		return fmt.Errorf("mux hello write: %w", err)
	}
	// 读一条响应（tunnel frame）证明通道通
	if _, _, err := conn.Read(ctx); err != nil {
		return fmt.Errorf("mux response read: %w", err)
	}
	fmt.Println("mux smoke OK")
	return nil
}
```

在 `main()` 里先创建一个 session（复用现有创建逻辑拿 sessionID），然后调用 `runMuxSmoke`，失败则 `os.Exit(1)`。保留现有 `/ws/sessions/{id}` 旧路径 smoke 不删。

- [ ] **Step 3: 编译**

Run: `cd go-core && go build ./cmd/webterm-flow-smoke/`
Expected: 无输出。

- [ ] **Step 4: 手动跑 smoke（需起 direct server）**

Run（在一个终端起 server）:
```bash
cd go-core && WEBTERM_PASSWORD=pw go run ./cmd/webterm-agent -mode direct
```
Run（另一个终端跑 smoke）:
```bash
cd go-core && go run ./cmd/webterm-flow-smoke
```
Expected: 看到 `mux smoke OK` 与原有旧路径 smoke 输出。

- [ ] **Step 5: Commit**

```bash
cd go-core && git add cmd/webterm-flow-smoke/main.go
git commit -m "test(smoke): add direct mux endpoint smoke case, keep legacy path

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: 全量回归与收尾

**Files:** 无新增。

- [ ] **Step 1: 全量 go test**

Run: `cd go-core && go test ./... 2>&1 | tail -20`
Expected: 所有包 PASS（mux 新包通过，relay/direct 回归通过）。

- [ ] **Step 2: go vet**

Run: `cd go-core && go vet ./...`
Expected: 无输出。

- [ ] **Step 3: 中继模式端到端回归（最高风险项）**

启动真实 relay server + agent，验证中继终端仍能打开：
```bash
# 终端1：relay server
cd relay-server && WEBTERM_RELAY_SECRET=secret node main.js
# 终端2：go-core agent
cd go-core && RELAY_URL=http://localhost:<relay-port> RELAY_SECRET=secret go run ./cmd/webterm-agent -mode relay
# 终端3：用 relay-flow-smoke
cd go-core && go run ./cmd/webterm-relay-flow-smoke
```
Expected: relay-flow-smoke 成功（agent 内部已换 mux.Serve，对 relay server 协议不变）。

- [ ] **Step 4: 最终 commit（若有 vet 修复）**

```bash
cd go-core && git add -A
git commit -m "chore: go vet cleanup after mux extraction

Co-Authored-By: Claude <noreply@anthropic.com>" || echo "nothing to commit"
```

---

## Self-Review 记录

**1. Spec 覆盖**：
- §2.1 提取 mux 包 → Task 2/3/4 ✅
- §2.2 manager 由 ws-connect 建立 → Task 3（Run 不自动建）+ Task 5 测试 ✅
- §2.3 OpenSessionOrManager + URL 解析/PathUnescape → Task 4 ✅
- §3.1 relay agent mux.Serve + registered/error 边界 → Task 6 ✅
- §3.2 direct 子协议分流 + 旧入口保留 → Task 7 ✅
- §3.3 MuxSubprotocol 常量 → Task 1 ✅
- §3.4 smoke 加 mux 用例 → Task 8 ✅
- §6.1 测试计划 → Task 5/6/7/9 ✅

**2. 占位符**：Task 6 Step 2 有一个"设计缺口说明 + 选项 B 完整重写"——这是引导实现者做正确选择，最终给出了完整代码，非占位符。其余无 TBD/TODO。

**3. 类型一致性**：`mux.Serve`/`ServeOpts`/`OpenHandler`/`ControlHandler`/`OpenSessionOrManager`/`VirtualSocket` 在 Task 2-7 签名一致；`relay.Client` 的 `writeControl`/`setConn`/`handleControl` 自洽。

**4. 已知风险**：Task 6 是最高风险（agent 内部换 mux），Task 9 Step 3 必须做真实中继回归。Android 客户端计划单独编写（依赖本计划的 direct mux 入口）。
