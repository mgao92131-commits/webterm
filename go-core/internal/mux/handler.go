package mux

import (
	"context"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/session"
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

// MuxServeAdapter 将 mux.Serve 适配为 application.MuxServeFunc，
// 避免 application → mux 的循环依赖。
func MuxServeAdapter(conn session.Socket, opts *application.MuxServeOpts) application.MuxSession {
	muxOpts := &ServeOpts{
		Logger: opts.Logger,
	}
	if opts.OnOpen != nil {
		muxOpts.OnOpen = func(ctx context.Context, vs *VirtualSocket, p string, protos []string) (func(), error) {
			return opts.OnOpen(ctx, vs, p, protos)
		}
	}
	if opts.OnControl != nil {
		muxOpts.OnControl = opts.OnControl
	}
	return Serve(conn, muxOpts)
}
