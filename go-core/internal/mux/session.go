package mux

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	termsession "webterm/go-core/internal/session"
)

// OpenHandler 为 logical channel 创建直接的帧处理器。
// handler.Run 在 ws-connected 成功写出后才启动，保证握手 ACK
// 先于 manager 初始列表或终端屏幕帧。
type OpenHandler func(
	ctx context.Context,
	sink termsession.ChannelFrameSink,
	path string,
	protocols []string,
) (termsession.LogicalChannelHandler, error)

// ControlHandler 处理 mux 不识别的设备级控制消息。
type ControlHandler func(ctx context.Context, source *Session, msg map[string]any)

type ServeOpts struct {
	OnOpen    OpenHandler
	OnControl ControlHandler
	Logger    *logs.Logger
}

type channelEntry struct {
	id      string
	handler termsession.LogicalChannelHandler
	sink    *channelSink
}

// Session 是一条 Android 设备连接的 actor。它解析 webterm.mux.v1 外层，
// 再把 channel payload 直接交给 handler，Agent 内不再伪造第二层 Socket。
type Session struct {
	conn       termsession.Socket
	writeMu    sync.Mutex
	channels   map[string]*channelEntry
	channelsMu sync.RWMutex
	onOpen     OpenHandler
	onControl  ControlHandler
	logger     *logs.Logger
}

func Serve(conn termsession.Socket, opts *ServeOpts) *Session {
	return &Session{
		conn:      conn,
		channels:  make(map[string]*channelEntry),
		onOpen:    opts.OnOpen,
		onControl: opts.OnControl,
		logger:    opts.Logger,
	}
}

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
		s.closeChannel(stringValue(msg["tunnelConnectionId"]))
	case protocol.WSConnected, protocol.WSError:
		// 服务端角色不应收到这两类回包。
	default:
		if s.onControl != nil {
			s.onControl(ctx, s, msg)
		}
	}
}

func (s *Session) handleWSConnect(ctx context.Context, msg map[string]any) {
	tunnelID := stringValue(msg["tunnelConnectionId"])
	path := stringValue(msg["path"])
	protocols := protocolsValue(msg["protocols"])
	if tunnelID == "" || s.onOpen == nil {
		return
	}

	sink := &channelSink{id: tunnelID, session: s}
	handler, err := s.onOpen(ctx, sink, cleanPath(path), protocols)
	if err != nil {
		_ = s.sendJSON(ctx, map[string]any{
			"type":               protocol.WSError,
			"tunnelConnectionId": tunnelID,
			"code":               http.StatusNotFound,
			"message":            err.Error(),
		})
		return
	}
	entry := &channelEntry{id: tunnelID, handler: handler, sink: sink}
	sink.entry = entry

	s.channelsMu.Lock()
	old := s.channels[tunnelID]
	s.channels[tunnelID] = entry
	s.channelsMu.Unlock()
	if old != nil {
		old.handler.Close()
	}

	if err := s.sendJSON(ctx, map[string]any{
		"type":               protocol.WSConnected,
		"tunnelConnectionId": tunnelID,
	}); err != nil {
		s.removeChannelIfCurrent(entry)
		handler.Close()
		return
	}

	go func() {
		handler.Run(ctx)
		if s.removeChannelIfCurrent(entry) {
			closeCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_ = s.sendJSON(closeCtx, map[string]any{
				"type":               protocol.WSClose,
				"tunnelConnectionId": tunnelID,
				"code":               int(websocket.StatusGoingAway),
				"reason":             "channel handler stopped",
			})
		}
	}()
}

func (s *Session) handleBinaryFrame(data []byte) {
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil || frame.MsgType != protocol.MsgTypeWSData {
		return
	}
	s.channelsMu.RLock()
	entry := s.channels[frame.ID]
	s.channelsMu.RUnlock()
	if entry != nil {
		entry.handler.HandleFrame(frame.Payload, frame.ExtraByte == protocol.WSDataBinary)
	}
}

func (s *Session) closeChannel(id string) {
	s.channelsMu.Lock()
	entry := s.channels[id]
	if entry != nil {
		delete(s.channels, id)
	}
	s.channelsMu.Unlock()
	if entry != nil {
		entry.handler.Close()
	}
}

func (s *Session) removeChannelIfCurrent(expected *channelEntry) bool {
	s.channelsMu.Lock()
	defer s.channelsMu.Unlock()
	if s.channels[expected.id] != expected {
		return false
	}
	delete(s.channels, expected.id)
	return true
}

func (s *Session) closeAllChannels() {
	s.channelsMu.Lock()
	entries := make([]*channelEntry, 0, len(s.channels))
	for _, entry := range s.channels {
		entries = append(entries, entry)
	}
	clear(s.channels)
	s.channelsMu.Unlock()
	for _, entry := range entries {
		entry.handler.Close()
	}
}

// SendControl 发送一条设备级文本控制消息。
func (s *Session) SendControl(ctx context.Context, msg map[string]any) error {
	return s.sendJSON(ctx, msg)
}

func (s *Session) writeChannelFrame(ctx context.Context, id string, payload []byte, binary bool) error {
	extra := protocol.WSDataText
	if binary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, extra, payload)
	if err != nil {
		return err
	}
	return s.writeBinary(ctx, frame)
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

type channelSink struct {
	id      string
	session *Session
	entry   *channelEntry
}

func (sink *channelSink) WriteFrame(ctx context.Context, payload []byte, binary bool) error {
	sink.session.channelsMu.RLock()
	current := sink.session.channels[sink.id] == sink.entry
	sink.session.channelsMu.RUnlock()
	if !current {
		return fmt.Errorf("channel %s closed", sink.id)
	}
	return sink.session.writeChannelFrame(ctx, sink.id, payload, binary)
}

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
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
