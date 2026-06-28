package relay

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
)

type connectionTransport struct {
	conn    *websocket.Conn
	mu      sync.RWMutex
	sockets map[string]*virtualSocket
}

func newConnectionTransport(conn *websocket.Conn) *connectionTransport {
	return &connectionTransport{
		conn:    conn,
		sockets: make(map[string]*virtualSocket),
	}
}

func (transport *connectionTransport) newSocket(id string, protocolName string) *virtualSocket {
	transport.mu.Lock()
	defer transport.mu.Unlock()
	socket := newVirtualSocket(id, protocolName, transport, func() {
		transport.removeSocket(id)
	})
	transport.sockets[id] = socket
	return socket
}

func (transport *connectionTransport) removeSocket(id string) {
	transport.mu.Lock()
	delete(transport.sockets, id)
	transport.mu.Unlock()
}

func (transport *connectionTransport) closeSocket(id string) {
	transport.mu.RLock()
	socket := transport.sockets[id]
	transport.mu.RUnlock()
	if socket != nil {
		_ = socket.Close()
	}
}

func (transport *connectionTransport) closeAll() {
	transport.mu.RLock()
	sockets := make([]*virtualSocket, 0, len(transport.sockets))
	for _, socket := range transport.sockets {
		sockets = append(sockets, socket)
	}
	transport.mu.RUnlock()
	for _, socket := range sockets {
		_ = socket.Close()
	}
}

func (transport *connectionTransport) handleBinaryFrame(data []byte) {
	frame, err := protocol.DecodeTunnelFrame(data)
	if err != nil {
		return
	}
	if frame.MsgType != protocol.MsgTypeWSData {
		return
	}
	transport.mu.RLock()
	socket := transport.sockets[frame.ID]
	transport.mu.RUnlock()
	if socket == nil {
		return
	}
	socket.Emit(frame.Payload, frame.ExtraByte == protocol.WSDataBinary)
}

func (transport *connectionTransport) sendJSON(ctx context.Context, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return transport.conn.Write(writeCtx, websocket.MessageText, bytes)
}

func (transport *connectionTransport) sendBinary(ctx context.Context, data []byte) error {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return transport.conn.Write(writeCtx, websocket.MessageBinary, data)
}
