package mux

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/diagnostics"
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
	ownerKey string,
) (termsession.LogicalChannelHandler, error)

// ControlHandler 处理 mux 不识别的设备级控制消息。
type ControlHandler func(ctx context.Context, source *Session, msg map[string]any)

type ServeOpts struct {
	OnOpen    OpenHandler
	OnControl ControlHandler
	Logger    *logs.Logger
}

// Session 是一条 Android 设备连接的 actor。它解析 webterm.mux.v1 外层，
// 再把 channel payload 直接交给 handler，Agent 内不再伪造第二层 Socket。
type Session struct {
	conn      termsession.Socket
	registry  *ChannelRegistry
	codec     ControlCodec
	onOpen    OpenHandler
	onControl ControlHandler
	logger    *logs.Logger
	writer    *PhysicalWriter
}

func Serve(conn termsession.Socket, opts *ServeOpts) *Session {
	return &Session{
		conn:      conn,
		registry:  NewChannelRegistry(),
		onOpen:    opts.OnOpen,
		onControl: opts.OnControl,
		logger:    opts.Logger,
		writer:    NewPhysicalWriter(conn, 128),
	}
}

func (s *Session) Run(ctx context.Context) error {
	writerCtx, cancelWriter := context.WithCancel(ctx)
	go s.writer.Run(writerCtx)
	defer func() {
		cancelWriter()
		<-s.writer.Done()
	}()
	defer s.closeAllChannels()
	return s.readLoop(ctx)
}

func (s *Session) readLoop(ctx context.Context) error {
	for {
		msgType, data, err := s.conn.Read(ctx)
		if err != nil {
			s.event("warn", "mux_read_failed", map[string]any{"reason": errorKind(err)})
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
	msg, err := s.codec.Decode(data)
	if err != nil {
		s.event("warn", "mux_control_decode_failed", map[string]any{
			"reason":       "protocol",
			"payloadBytes": len(data),
		})
		return
	}
	switch msg.Type {
	case protocol.WSConnect:
		s.handleWSConnect(ctx, msg)
	case protocol.WSClose:
		s.closeChannel(msg.TunnelConnectionID)
	case protocol.WSConnected, protocol.WSError:
		// 服务端角色不应收到这两类回包。
	default:
		if s.onControl != nil {
			s.onControl(ctx, s, msg.Raw)
		}
	}
}

func (s *Session) handleWSConnect(ctx context.Context, msg ControlMessage) {
	tunnelID := msg.TunnelConnectionID
	routeKey := msg.ChannelRouteKey
	ownerKey := msg.ChannelOwnerKey
	path := msg.Path
	protocols := msg.Protocols
	if tunnelID == "" || s.onOpen == nil {
		return
	}
	if len(routeKey) > 1024 {
		routeKey = ""
	}
	if len(ownerKey) > 2048 {
		ownerKey = ""
	}

	sink := &channelSink{id: tunnelID, session: s}
	handler, err := s.onOpen(ctx, sink, cleanPath(path), protocols, ownerKey)
	if err != nil {
		_ = s.sendJSON(ctx, s.codec.Error(tunnelID, http.StatusNotFound, err.Error()))
		return
	}
	entry := &channelEntry{id: tunnelID, routeKey: routeKey, handler: handler, sink: sink}
	sink.entry = entry

	oldByID, oldByRoute := s.registry.Replace(entry)
	if oldByID != nil {
		diagnostics.Default.MuxChannelReplacedCount.Add(1)
		s.event("info", "mux_channel_replaced", map[string]any{
			"channelId": logs.SafeID(tunnelID),
			"reason":    "id",
		})
		oldByID.handler.Close()
	}
	if oldByRoute != nil && oldByRoute != oldByID {
		diagnostics.Default.MuxChannelReplacedCount.Add(1)
		s.event("info", "mux_channel_replaced", map[string]any{
			"channelId": logs.SafeID(tunnelID),
			"reason":    "route",
		})
		oldByRoute.handler.Close()
	}

	if err := s.sendJSON(ctx, s.codec.Connected(tunnelID)); err != nil {
		s.removeChannelIfCurrent(entry)
		handler.Close()
		return
	}
	diagnostics.Default.MuxChannelOpenedCount.Add(1)
	s.event("info", "mux_channel_open", map[string]any{"channelId": logs.SafeID(tunnelID)})

	go func() {
		handler.Run(ctx)
		if s.removeChannelIfCurrent(entry) {
			closeCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_ = s.sendJSON(closeCtx, s.codec.Close(tunnelID, int(websocket.StatusGoingAway), "channel handler stopped"))
		}
	}()
}

func (s *Session) handleBinaryFrame(data []byte) {
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil || frame.MsgType != protocol.MsgTypeWSData {
		if err != nil {
			s.event("warn", "mux_binary_decode_failed", map[string]any{
				"reason":       "protocol",
				"payloadBytes": len(data),
			})
		}
		return
	}
	entry := s.registry.Get(frame.ID)
	if entry != nil {
		entry.handler.HandleFrame(frame.Payload, frame.ExtraByte == protocol.WSDataBinary)
	}
}

func (s *Session) closeChannel(id string) {
	entry := s.registry.Remove(id)
	if entry != nil {
		s.event("info", "mux_channel_closed", map[string]any{"channelId": logs.SafeID(id)})
		entry.handler.Close()
	}
}

func (s *Session) removeChannelIfCurrent(expected *channelEntry) bool {
	return s.registry.RemoveIfCurrent(expected)
}

func (s *Session) closeAllChannels() {
	entries := s.registry.Drain()
	for _, entry := range entries {
		entry.handler.Close()
	}
}

// SendControl 发送一条设备级文本控制消息。
func (s *Session) SendControl(ctx context.Context, msg map[string]any) error {
	return s.sendJSON(ctx, msg)
}

func (s *Session) writeChannelFrame(ctx context.Context, id string, payload []byte, binary bool) error {
	return s.writeChannelFramePriority(ctx, id, payload, binary, termsession.FramePriorityNormal)
}

func (s *Session) writeChannelFramePriority(ctx context.Context, id string, payload []byte,
	binary bool, priority termsession.FramePriority) error {
	extra := protocol.WSDataText
	if binary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, extra, payload)
	if err != nil {
		return err
	}
	err = s.writer.Submit(ctx, termsession.MessageBinary, frame, priority == termsession.FramePriorityHigh)
	s.logWriteError(err)
	return err
}

func (s *Session) writeBinary(ctx context.Context, data []byte) error {
	err := s.writer.Submit(ctx, termsession.MessageBinary, data, false)
	s.logWriteError(err)
	return err
}

func (s *Session) sendJSON(ctx context.Context, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	err = s.writer.Submit(ctx, termsession.MessageText, bytes, true)
	s.logWriteError(err)
	return err
}

func (s *Session) logWriteError(err error) {
	if err == nil {
		return
	}
	diagnostics.Default.MuxWriterFailureCount.Add(1)
	event := "mux_writer_failed"
	if errors.Is(err, context.DeadlineExceeded) {
		event = "mux_writer_timeout"
	}
	s.event("warn", event, map[string]any{"reason": errorKind(err)})
}

// event 统一走 Logger.Event：事件名稳定、字段经脱敏构造、由 5 秒窗口限流。
// 原始错误文本（可能含地址等信息）不进日志，只保留 errorKind 分类。
func (s *Session) event(level, event string, fields map[string]any) {
	if s.logger == nil {
		return
	}
	s.logger.Event(level, "mux", event, fields)
}

// errorKind 把错误归类为少量稳定枚举，作为事件 reason 字段。
// 协议解析失败不由本函数归类，调用点直接写 reason=protocol。
func errorKind(err error) string {
	switch {
	case err == nil:
		return "none"
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, net.ErrClosed), errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF):
		return "closed"
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timeout"
	}
	return "unknown"
}

type channelSink struct {
	id      string
	session *Session
	entry   *channelEntry
}

func (sink *channelSink) WriteFrame(ctx context.Context, payload []byte, binary bool) error {
	return sink.WriteFramePriority(ctx, payload, binary, termsession.FramePriorityNormal)
}

func (sink *channelSink) WriteFramePriority(ctx context.Context, payload []byte, binary bool,
	priority termsession.FramePriority) error {
	if !sink.session.registry.IsCurrent(sink.entry) {
		return fmt.Errorf("channel %s closed", sink.id)
	}
	return sink.session.writeChannelFramePriority(ctx, sink.id, payload, binary, priority)
}

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
}
