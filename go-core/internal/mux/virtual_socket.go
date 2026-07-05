package mux

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/logs"
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
	logger    *logs.Logger
}

func newVirtualSocket(id string, protocolName string, s *Session, onClose func(), logger *logs.Logger) *VirtualSocket {
	return &VirtualSocket{
		id:        id,
		protocol:  protocolName,
		session:   s,
		incoming:  make(chan virtualMessage, 256),
		done:      make(chan struct{}),
		onClose:   onClose,
		logger:    logger,
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
		if socket.logger != nil {
			socket.logger.Add("warn", "mux", fmt.Sprintf("virtual socket incoming buffer full, closing id=%s", socket.id))
		}
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
