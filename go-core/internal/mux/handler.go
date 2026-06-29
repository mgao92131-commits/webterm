package mux

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"webterm/go-core/internal/session"
)

// OpenSessionOrManager 是 direct server 和 relay agent 共用的 OnOpen 处理器。
// 根据 path 决定建 ManagerClient 还是终端 Client。返回 error 时由 mux 发 ws-error。
func OpenSessionOrManager(
	ctx context.Context,
	manager *session.Manager,
	vs *VirtualSocket,
	path string,
	protocols []string,
) error {
	switch {
	case path == "/ws/sessions":
		mc := session.NewManagerClient(vs)
		go mc.Run(ctx, manager)
		return nil

	case strings.HasPrefix(path, "/ws/sessions/"):
		id := strings.TrimPrefix(path, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := manager.Get(id)
		if !ok {
			return fmt.Errorf("session %s not found", id)
		}
		mode := session.ClientModeFromProtocol(selectProtocol(protocols))
		client := session.NewClient(vs, terminal, mode)
		go client.Run(ctx)
		return nil

	default:
		return fmt.Errorf("unknown path: %s", path)
	}
}
