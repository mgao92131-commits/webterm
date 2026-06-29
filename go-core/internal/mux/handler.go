package mux

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"webterm/go-core/internal/session"
)

// OpenSessionOrManager 是 direct server 和 relay agent 共用的 OnOpen 处理器。
// 根据 path 决定建 ManagerClient 还是终端 Client，但暂不启动——返回的 start 由
// mux 在 ws-connected 写出成功后调用，保证握手 ack 先于通道数据。返回 error 时
// 由 mux 发 ws-error。
func OpenSessionOrManager(
	ctx context.Context,
	manager *session.Manager,
	vs *VirtualSocket,
	path string,
	protocols []string,
) (func(), error) {
	switch {
	case path == "/ws/sessions":
		mc := session.NewManagerClient(vs)
		return func() { go mc.Run(ctx, manager) }, nil

	case strings.HasPrefix(path, "/ws/sessions/"):
		id := strings.TrimPrefix(path, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := manager.Get(id)
		if !ok {
			return nil, fmt.Errorf("session %s not found", id)
		}
		mode := session.ClientModeFromProtocol(selectProtocol(protocols))
		client := session.NewClient(vs, terminal, mode)
		return func() { go client.Run(ctx) }, nil

	default:
		return nil, fmt.Errorf("unknown path: %s", path)
	}
}
