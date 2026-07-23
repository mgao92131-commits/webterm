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

	"google.golang.org/protobuf/proto"
	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/protocol"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
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
	router := application.NewSessionRouter(manager)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			Subprotocols: []string{protocol.MuxSubprotocol},
		})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		sess := Serve(session.NewWebSocketAdapter(conn), &ServeOpts{
			OnOpen: func(ctx context.Context, sink session.ChannelFrameSink, path string, protocols []string, ownerKey string) (session.LogicalChannelHandler, error) {
				return OpenSessionOrManager(ctx, router, sink, path, protocols, ownerKey)
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

func TestMuxDuplicateChannelIDReplacesChannel(t *testing.T) {
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

	openManager := func() {
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
		frame := readTunnel(t, ctx, conn)
		if frame.ID != "manager" || !strings.Contains(string(frame.Payload), `"type":"sessions"`) {
			t.Fatalf("manager frame = %#v payload=%s", frame, frame.Payload)
		}
	}

	openManager()
	// Replaying the same logical ID is the terminal-page reattach path. It must
	// replace the old handler without deadlocking the mux read loop.
	openManager()
}

func TestMuxDuplicateTerminalChannelReleasesReplacedClient(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}

	openTerminal := func() {
		writeJSONMsg(t, ctx, conn, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": "term-repro",
			"path":               "/ws/sessions/" + terminal.ID(),
			"protocols":          []string{protocol.ScreenSubprotocol},
		})
		connected := readJSON(t, ctx, conn)
		if connected["type"] != protocol.WSConnected {
			t.Fatalf("ws-connected = %#v", connected)
		}
	}

	openTerminal()
	waitTerminalClients(t, terminal, 1)
	openTerminal()
	// 同一物理 mux 内重复打开同一 logical channel 时，旧 handler 必须退出；
	// 若这里稳定为 1，s8 的 11 clients 就不是该替换路径自身造成的泄漏。
	waitTerminalClients(t, terminal, 1)

	_ = conn.Close(websocket.StatusNormalClosure, "")
	waitTerminalClients(t, terminal, 0)
}

func TestMuxScreenRouteReplacesDifferentTunnelID(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	openTerminal := func(tunnelID string) {
		writeJSONMsg(t, ctx, conn, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": tunnelID,
			"channelRouteKey":    "term:" + terminal.ID() + ":" + protocol.ScreenSubprotocol,
			"path":               "/ws/sessions/" + terminal.ID(),
			"protocols":          []string{protocol.ScreenSubprotocol},
		})
		connected := readJSON(t, ctx, conn)
		if connected["type"] != protocol.WSConnected || connected["tunnelConnectionId"] != tunnelID {
			t.Fatalf("ws-connected = %#v", connected)
		}
	}

	openTerminal("term-owner-a")
	waitTerminalClients(t, terminal, 1)
	openTerminal("term-owner-b")
	// owner-b 使用不同 tunnel ID，仍须按稳定 route 原子接管 owner-a。
	waitTerminalClients(t, terminal, 1)
}

func TestMuxScreenOwnerReplacesHandlerAcrossPhysicalConnections(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	terminal, err := manager.Create(".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	wsURL, cleanup := startMuxServer(t, ctx, manager)
	defer cleanup()

	dial := func() *websocket.Conn {
		conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
			Subprotocols: []string{protocol.MuxSubprotocol},
		})
		if err != nil {
			t.Fatalf("dial: %v", err)
		}
		return conn
	}
	first := dial()
	defer first.Close(websocket.StatusNormalClosure, "")
	second := dial()
	defer second.Close(websocket.StatusNormalClosure, "")

	openTerminal := func(conn *websocket.Conn, tunnelID string) {
		writeJSONMsg(t, ctx, conn, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": tunnelID,
			"channelRouteKey":    "term:" + terminal.ID() + ":" + protocol.ScreenSubprotocol,
			"channelOwnerKey":    "android-connection-a:term:" + terminal.ID(),
			"path":               "/ws/sessions/" + terminal.ID(),
			"protocols":          []string{protocol.ScreenSubprotocol},
		})
		connected := readJSON(t, ctx, conn)
		if connected["type"] != protocol.WSConnected || connected["tunnelConnectionId"] != tunnelID {
			t.Fatalf("ws-connected = %#v", connected)
		}
	}

	openTerminal(first, "term-physical-a")
	waitTerminalClients(t, terminal, 1)
	openTerminal(second, "term-physical-b")
	// 同一 Android DeviceConnection 的旧物理 Mux 即使仍半开，也只能保留新 handler。
	waitTerminalClients(t, terminal, 1)
}

func waitTerminalClients(t *testing.T, terminal interface{ Info() session.Info }, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if terminal.Info().Clients == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("terminal clients = %d, want %d", terminal.Info().Clients, want)
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
	terminal, err := manager.Create(".")
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
		"protocols":          []string{protocol.ScreenSubprotocol},
	})
	connected := readJSON(t, ctx, conn)
	if connected["type"] != protocol.WSConnected {
		t.Fatalf("ws-connected = %#v", connected)
	}

	// 发 screen hello（protobuf 帧经 tunnel frame）。
	helloFrame, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "mux-test", StreamGeneration: 1,
			DesiredMode: pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			DesiredGeometry: &pb.Geometry{Cols: 80, Rows: 24},
		}},
	})
	if err != nil {
		t.Fatalf("marshal screen hello: %v", err)
	}
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
		t.Fatalf("expected tunnel frame, got % x", data[:minInt(len(data), 8)])
	}
	cancel()
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// TestMuxWSConnectedIsFirstMessage 守住握手顺序不变量：ws-connect 之后服务端
// 落线的第一条消息必须是 text 类型的 ws-connected，不能是 tunnel data 帧
// （terminal info / manager 初始 sessions 列表不得抢在 ack 之前）。
func TestMuxWSConnectedIsFirstMessage(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	manager := newManagerWithShell(t)
	terminal, err := manager.Create(".")
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
		"protocols":          []string{protocol.ScreenSubprotocol},
	})

	readCtx, readCancel := context.WithTimeout(ctx, 3*time.Second)
	defer readCancel()
	msgType, data, err := conn.Read(readCtx)
	if err != nil {
		t.Fatalf("read first message: %v", err)
	}
	if msgType != websocket.MessageText {
		t.Fatalf("first message must be text ws-connected, got binary tunnel frame: % x", data[:minInt(len(data), 8)])
	}
	var first map[string]any
	if err := json.Unmarshal(data, &first); err != nil {
		t.Fatalf("first message is not JSON: %v (raw: % x)", err, data[:minInt(len(data), 8)])
	}
	if first["type"] != protocol.WSConnected || first["tunnelConnectionId"] != "term1" {
		t.Fatalf("first message = %#v, want ws-connected/term1", first)
	}
	cancel()
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
	readJSON(t, ctx, conn)   // ws-connected
	readTunnel(t, ctx, conn) // initial sessions push

	// 发 ws-close，服务端应关闭该 logical channel（无 panic 即可）。
	writeJSONMsg(t, ctx, conn, map[string]any{
		"type":               protocol.WSClose,
		"tunnelConnectionId": "m1",
	})
	// 给一点时间让关闭生效。
	time.Sleep(100 * time.Millisecond)
	cancel()
}
