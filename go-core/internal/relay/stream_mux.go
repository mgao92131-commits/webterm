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
	router  *application.SessionRouter
	writer  frameWriter
	mu      sync.Mutex
	streams map[string]*relayStreamSocket
}

type relayStreamMessage struct {
	messageType session.MessageType
	payload     []byte
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
