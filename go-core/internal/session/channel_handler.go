package session

import (
	"context"

	"webterm/go-core/internal/logs"
)

// ChannelFrameSink 是 logical channel 向物理设备连接写帧的最小边界。
// 它不暴露 Read/Close 或 WebSocket 语义；channel 的生命周期由外层连接 actor 管理。
type ChannelFrameSink interface {
	WriteFrame(ctx context.Context, payload []byte, binary bool) error
}

// LogicalChannelHandler 直接承接 mux logical channel 帧。
// Run 在 channel 存活期内阻塞；HandleFrame 由外层连接 actor 串行调用。
type LogicalChannelHandler interface {
	Run(ctx context.Context)
	HandleFrame(payload []byte, binary bool)
	Close()
}

// TerminalChannelHandler 把 webterm.screen.v1 帧直接连到权威 Terminal Runtime。
//
// 内部协议状态只保留 baseline/mailbox/single-writer，输入由 handler
// 直接投递，输出只依赖 ChannelFrameSink。
type TerminalChannelHandler struct {
	runtime *terminalChannelRuntime
}

func NewTerminalChannelHandler(terminal *TerminalSession, sink ChannelFrameSink, logger ...*logs.Logger) *TerminalChannelHandler {
	return &TerminalChannelHandler{
		runtime: newTerminalChannelRuntime(terminal, sink, logger...),
	}
}

func (handler *TerminalChannelHandler) Run(ctx context.Context) {
	handler.runtime.run(ctx)
}

func (handler *TerminalChannelHandler) HandleFrame(payload []byte, binary bool) {
	if binary {
		handler.runtime.handleScreenBinary(payload)
	}
}

func (handler *TerminalChannelHandler) Close() {
	handler.runtime.Close()
}

// ManagerChannelHandler 是 manager logical channel 的无 Socket 适配。
// manager channel 当前只向 Android 推送列表变化，不消费 channel 内的上行帧。
type ManagerChannelHandler struct {
	client  *ManagerClient
	manager *Manager
}

func NewManagerChannelHandler(manager *Manager, sink ChannelFrameSink, logger ...*logs.Logger) *ManagerChannelHandler {
	return &ManagerChannelHandler{
		client:  NewManagerClient(sink, logger...),
		manager: manager,
	}
}

func (handler *ManagerChannelHandler) Run(ctx context.Context) {
	handler.client.run(ctx, handler.manager)
}

func (handler *ManagerChannelHandler) HandleFrame([]byte, bool) {}

func (handler *ManagerChannelHandler) Close() {
	handler.client.Close()
}
