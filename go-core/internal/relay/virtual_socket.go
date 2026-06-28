package relay

import (
	"context"
	"errors"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

type relayTransport interface {
	sendJSON(context.Context, any) error
	sendBinary(context.Context, []byte) error
}

type virtualSocket struct {
	id        string
	protocol  string
	transport relayTransport
	incoming  chan virtualMessage
	done      chan struct{}
	closeOnce sync.Once
	onClose   func()
}

type virtualMessage struct {
	messageType session.MessageType
	payload     []byte
}

func newVirtualSocket(id string, protocolName string, transport relayTransport, onClose func()) *virtualSocket {
	return &virtualSocket{
		id:        id,
		protocol:  protocolName,
		transport: transport,
		incoming:  make(chan virtualMessage, 256),
		done:      make(chan struct{}),
		onClose:   onClose,
	}
}

func (socket *virtualSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case <-socket.done:
		return 0, nil, errors.New("virtual socket closed")
	case message := <-socket.incoming:
		return message.messageType, message.payload, nil
	}
}

func (socket *virtualSocket) Write(ctx context.Context, messageType session.MessageType, data []byte) error {
	extra := protocol.WSDataText
	if messageType == session.MessageBinary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, socket.id, extra, data)
	if err != nil {
		return err
	}
	return socket.transport.sendBinary(ctx, frame)
}

func (socket *virtualSocket) Close() error {
	socket.close(false)
	return nil
}

func (socket *virtualSocket) CloseWithNotify(ctx context.Context, code int, reason string) {
	socket.closeOnce.Do(func() {
		close(socket.done)
		if socket.onClose != nil {
			socket.onClose()
		}
		_ = socket.transport.sendJSON(ctx, map[string]any{
			"type":               protocol.WSClose,
			"tunnelConnectionId": socket.id,
			"code":               code,
			"reason":             reason,
		})
	})
}

func (socket *virtualSocket) Subprotocol() string {
	return socket.protocol
}

func (socket *virtualSocket) Emit(payload []byte, binary bool) bool {
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

func (socket *virtualSocket) close(notify bool) {
	socket.closeOnce.Do(func() {
		close(socket.done)
		if socket.onClose != nil {
			socket.onClose()
		}
		if notify {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_ = socket.transport.sendJSON(ctx, map[string]any{
				"type":               protocol.WSClose,
				"tunnelConnectionId": socket.id,
				"code":               websocket.StatusNormalClosure,
				"reason":             "",
			})
		}
	})
}
