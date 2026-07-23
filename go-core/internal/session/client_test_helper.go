package session

import (
	"context"

	"webterm/go-core/internal/logs"
)

type ClientMode string

const ClientModeScreen ClientMode = "screen"

type testSocketFrameSink struct{ socket Socket }

func (sink testSocketFrameSink) Subprotocol() string {
	if socket, ok := sink.socket.(interface{ Subprotocol() string }); ok {
		return socket.Subprotocol()
	}
	return ""
}

func (sink testSocketFrameSink) WriteFrame(ctx context.Context, payload []byte, binary bool) error {
	messageType := MessageText
	if binary {
		messageType = MessageBinary
	}
	return sink.socket.Write(ctx, messageType, payload)
}

func newTestTerminalChannelRuntime(socket Socket, terminal *TerminalSession, _ ClientMode, logger ...*logs.Logger) *terminalChannelRuntime {
	return newTerminalChannelRuntime(terminal, testSocketFrameSink{socket: socket}, logger...)
}

// SetReadyForTest 将 terminalChannelRuntime 标记为 ready，仅供测试使用。
func (client *terminalChannelRuntime) SetReadyForTest() {
	if client == nil {
		return
	}
	client.ready.Store(true)
}
