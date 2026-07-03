package mux

import (
	"context"

	"webterm/go-core/internal/application"
)

// OpenSessionOrManager 是 direct server 和 relay agent 共用的 OnOpen 处理器。
// 委托给 application.SessionRouter 统一处理路径分发。
// 返回的 start 在 ws-connected 写出成功后由 mux 调用，保证握手 ack 先于通道数据。
func OpenSessionOrManager(
	ctx context.Context,
	router *application.SessionRouter,
	vs *VirtualSocket,
	path string,
	protocols []string,
) (func(), error) {
	return router.RouteOpen(ctx, vs, path, protocols)
}
