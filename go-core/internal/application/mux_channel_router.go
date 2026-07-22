package application

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// MuxChannelRouter 只拥有 manager/screen logical channel 路由与 subprotocol 校验。
type MuxChannelRouter struct {
	manager *session.Manager
	logger  *logs.Logger
}

func NewMuxChannelRouter(manager *session.Manager, logger *logs.Logger) *MuxChannelRouter {
	return &MuxChannelRouter{manager: manager, logger: logger}
}

func (router *MuxChannelRouter) Open(
	ctx context.Context,
	sink session.ChannelFrameSink,
	path string,
	protocols []string,
) (session.LogicalChannelHandler, error) {
	return router.OpenOwned(ctx, sink, path, protocols, "")
}

func (router *MuxChannelRouter) OpenOwned(
	_ context.Context,
	sink session.ChannelFrameSink,
	path string,
	protocols []string,
	ownerKey string,
) (session.LogicalChannelHandler, error) {
	clean := cleanPath(path)
	switch {
	case clean == "/ws/sessions":
		return session.NewManagerChannelHandler(router.manager, sink, router.logger), nil
	case strings.HasPrefix(clean, "/ws/sessions/"):
		if !hasProtocol(protocols, protocol.ScreenSubprotocol) {
			return nil, fmt.Errorf("terminal sessions require %s", protocol.ScreenSubprotocol)
		}
		id := strings.TrimPrefix(clean, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := router.manager.Get(id)
		if !ok {
			return nil, fmt.Errorf("session %s not found", id)
		}
		return session.NewOwnedTerminalChannelHandler(terminal, sink, ownerKey, router.logger), nil
	case strings.HasPrefix(clean, "/ws/capture/"):
		// 现场捕获逻辑通道：独立于 screen 通道的诊断数据面。与 screen 通道一样经
		// path 携带 sessionId、由 Manager.Get 解析，Direct/Relay 均透明路由；Relay 不
		// 解析、不持久化其正文。生产构建（未开启 webterm_capture）handler 会直接拒绝。
		if !hasProtocol(protocols, protocol.CaptureSubprotocol) {
			return nil, fmt.Errorf("capture channel requires %s", protocol.CaptureSubprotocol)
		}
		id := strings.TrimPrefix(clean, "/ws/capture/")
		id, _ = url.PathUnescape(id)
		terminal, ok := router.manager.Get(id)
		if !ok {
			return nil, fmt.Errorf("session %s not found", id)
		}
		return session.NewCaptureChannelHandler(terminal, sink, router.logger), nil
	default:
		return nil, fmt.Errorf("unknown path: %s", path)
	}
}

func hasProtocol(protocols []string, target string) bool {
	for _, candidate := range protocols {
		if candidate == target {
			return true
		}
	}
	return false
}
