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
}

// SocketPartsWriter 是可选的 scatter/gather 写能力。实现必须把 parts 作为同一个
// WebSocket message 顺序写出；PhysicalWriter 在不支持时回退为一次合并拷贝。
type SocketPartsWriter interface {
	WriteParts(context.Context, MessageType, ...[]byte) error
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

func (adapter *WebSocketAdapter) WriteParts(ctx context.Context, messageType MessageType, parts ...[]byte) error {
	websocketType := websocket.MessageText
	if messageType == MessageBinary {
		websocketType = websocket.MessageBinary
	}
	writer, err := adapter.conn.Writer(ctx, websocketType)
	if err != nil {
		return err
	}
	for _, part := range parts {
		if len(part) == 0 {
			continue
		}
		if _, err := writer.Write(part); err != nil {
			_ = writer.Close()
			return err
		}
	}
	return writer.Close()
}

func (adapter *WebSocketAdapter) Close() error {
	return adapter.conn.Close(websocket.StatusNormalClosure, "")
}
