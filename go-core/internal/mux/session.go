package mux

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	termsession "webterm/go-core/internal/session"
)

// OpenHandler 处理一个新建立的虚拟通道：创建上层客户端但暂不启动。
// 返回的 start 在 ws-connected 发送成功后由 mux 调用，确保握手 ack 先于
// 任何通道数据落线（否则 client.Run 的 SendInfo / manager 初始列表可能抢在
// ws-connected 之前写出，违反握手顺序）。返回 error 时由 mux 发 ws-error。
type OpenHandler func(ctx context.Context, vs *VirtualSocket, path string, protocols []string) (start func(), err error)

// ControlHandler 处理 mux 不识别的控制消息，必要时透传给上层。
type ControlHandler func(ctx context.Context, msg map[string]any)

type ServeOpts struct {
	OnOpen    OpenHandler    // 必填
	OnControl ControlHandler // 可选
	Logger    *logs.Logger   // 可选，用于背压关闭日志
}

type Session struct {
	conn       termsession.Socket
	writeMu    sync.Mutex
	channels   map[string]*VirtualSocket
	channelsMu sync.RWMutex
	onOpen     OpenHandler
	onControl  ControlHandler
	logger     *logs.Logger
}

// Serve 包装一个已建立的 WebSocket 连接，启动多路复用。
// 调用方负责 conn 的建立（direct 用 websocket.Accept；relay 用 websocket.Dial + register）。
func Serve(conn termsession.Socket, opts *ServeOpts) *Session {
	return &Session{
		conn:      conn,
		channels:  make(map[string]*VirtualSocket),
		onOpen:    opts.OnOpen,
		onControl: opts.OnControl,
		logger:    opts.Logger,
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
		case termsession.MessageText:
			s.handleControlMessage(ctx, data)
		case termsession.MessageBinary:
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
	start, err := s.onOpen(ctx, vs, cleanPath(path), protocols)
	if err != nil {
		s.removeSocket(tunnelID)
		_ = s.sendJSON(ctx, map[string]any{
			"type":               protocol.WSError,
			"tunnelConnectionId": tunnelID,
			"code":               http.StatusNotFound,
			"message":            err.Error(),
		})
		return
	}
	// 先发 ws-connected 并等其写出成功，再启动客户端，保证 ack 先于通道数据。
	if err := s.sendJSON(ctx, map[string]any{
		"type":               protocol.WSConnected,
		"tunnelConnectionId": tunnelID,
	}); err != nil {
		s.removeSocket(tunnelID)
		return
	}
	if start != nil {
		go start()
	}
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
	}, s.logger)
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
	return s.conn.Write(writeCtx, termsession.MessageBinary, data)
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
	return s.conn.Write(writeCtx, termsession.MessageText, bytes)
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
	return fmt.Sprint(value)
}
