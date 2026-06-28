package session

import (
	"context"

	"nhooyr.io/websocket"
)

type MessageType int

const (
	MessageText MessageType = iota + 1
	MessageBinary
)

type Socket interface {
	Read(context.Context) (MessageType, []byte, error)
	Write(context.Context, MessageType, []byte) error
	Close() error
	Subprotocol() string
}

type WebSocketAdapter struct {
	conn *websocket.Conn
}

func NewWebSocketAdapter(conn *websocket.Conn) *WebSocketAdapter {
	return &WebSocketAdapter{conn: conn}
}

func (adapter *WebSocketAdapter) Read(ctx context.Context) (MessageType, []byte, error) {
	messageType, data, err := adapter.conn.Read(ctx)
	if err != nil {
		return 0, nil, err
	}
	if messageType == websocket.MessageBinary {
		return MessageBinary, data, nil
	}
	return MessageText, data, nil
}

func (adapter *WebSocketAdapter) Write(ctx context.Context, messageType MessageType, data []byte) error {
	if messageType == MessageBinary {
		return adapter.conn.Write(ctx, websocket.MessageBinary, data)
	}
	return adapter.conn.Write(ctx, websocket.MessageText, data)
}

func (adapter *WebSocketAdapter) Close() error {
	return adapter.conn.Close(websocket.StatusNormalClosure, "")
}

func (adapter *WebSocketAdapter) Subprotocol() string {
	return adapter.conn.Subprotocol()
}
