package mux

import (
	"context"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/session"
)

// OpenSessionOrManager 把 logical channel 直接路由到 manager 或 Terminal Runtime。
func OpenSessionOrManager(
	ctx context.Context,
	router *application.SessionRouter,
	sink session.ChannelFrameSink,
	path string,
	protocols []string,
	ownerKey string,
) (session.LogicalChannelHandler, error) {
	return router.OpenOwnedLogicalChannel(ctx, sink, path, protocols, ownerKey)
}

// MuxServeAdapter 将 mux.Serve 适配为 application.MuxServeFunc，
// 避免 application → mux 的循环依赖。
func MuxServeAdapter(conn session.Socket, opts *application.MuxServeOpts) application.MuxSession {
	muxOpts := &ServeOpts{
		Logger: opts.Logger,
	}
	if opts.OnOpen != nil {
		muxOpts.OnOpen = func(ctx context.Context, sink session.ChannelFrameSink, p string, protos []string, ownerKey string) (session.LogicalChannelHandler, error) {
			return opts.OnOpen(ctx, sink, p, protos, ownerKey)
		}
	}
	if opts.OnControl != nil {
		muxOpts.OnControl = func(ctx context.Context, source *Session, msg map[string]any) {
			opts.OnControl(ctx, source, msg)
		}
	}
	return Serve(conn, muxOpts)
}
