package application

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"

	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// MuxServeFunc 是 mux.Serve 的函数签名，用于避免 application → mux 的循环依赖。
// Relay Agent 负责传入 mux.Serve 的实际实现。
type MuxServeFunc func(conn session.Socket, opts *MuxServeOpts) MuxSession

// MuxSession 是 mux.Session 的接口抽象。
type MuxSession interface {
	Run(ctx context.Context) error
	// SendControl 发送设备级控制消息（file_send.* / agent_notification）。
	// *mux.Session 已实现；签名与 filesend.ControlSender 一致，便于在 relay 代理侧
	// 把重建出的 mux session 直接注册为设备 ControlSender。
	SendControl(ctx context.Context, msg map[string]any) error
}

// MuxServeOpts 是 mux.ServeOpts 的类型投影。logical channel 直接交付
// frame sink 和 handler，不再投影虚拟 WebSocket。
type MuxServeOpts struct {
	OnOpen    func(ctx context.Context, sink session.ChannelFrameSink, path string, protocols []string, ownerKey string) (session.LogicalChannelHandler, error)
	OnControl func(ctx context.Context, source MuxSession, msg map[string]any)
	Logger    *logs.Logger
}

// SessionRouter 统一 session 路径分发和 CRUD 逻辑，
// 供 Relay Agent 统一处理 mux channel。
type SessionRouter struct {
	manager     *session.Manager
	muxServe    MuxServeFunc // 可选：用于 mux 子协议包装
	controls    *ControlDispatcher
	fileSend    *filesend.Service
	sessionHTTP *SessionHTTPHandler
	transfers   *TransferHTTPHandler
	channels    *MuxChannelRouter
	logger      *logs.Logger
}

func NewSessionRouter(manager *session.Manager, logger ...*logs.Logger) *SessionRouter {
	return NewSessionRouterWithMux(manager, nil, logger...)
}

// NewSessionRouterWithMux 创建带 mux 支持的 SessionRouter。
// muxServe 应该是 mux.Serve 的实际实现，由调用方注入以避免循环依赖。
func NewSessionRouterWithMux(manager *session.Manager, muxServe MuxServeFunc, logger ...*logs.Logger) *SessionRouter {
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	sessionHTTP := NewSessionHTTPHandler(manager)
	return &SessionRouter{
		manager: manager, muxServe: muxServe, controls: NewControlDispatcher(), logger: log,
		sessionHTTP: sessionHTTP, transfers: NewTransferHTTPHandler(sessionHTTP),
		channels: NewMuxChannelRouter(manager, log),
	}
}

// SetControlHandler 设置 mux 设备级控制消息处理器。
// 用于 file_send.*、agent_notification 等不经过虚拟通道的控制消息。
func (r *SessionRouter) SetControlHandler(onControl func(ctx context.Context, source MuxSession, msg map[string]any)) {
	r.controls.SetFallback(onControl)
}

// SetFileSendService 注入 FileSendService，并显式注册其 control message types。
func (r *SessionRouter) SetFileSendService(svc *filesend.Service) {
	r.fileSend = svc
	r.transfers.SetFileSendService(svc)
	handler := func(ctx context.Context, source MuxSession, msg map[string]any) {
		if svc != nil {
			svc.HandleControlFrom(ctx, source, msg)
		}
	}
	for _, messageType := range []string{
		"client.register", "client.active",
		filesend.TypeAccepted, filesend.TypeRejected, filesend.TypeProgress,
		filesend.TypeSaving, filesend.TypeSaved, filesend.TypeFailed, filesend.TypeCancelled,
	} {
		r.controls.Register(messageType, handler)
	}
}

// SetFileUploadService 注入 FileUploadService，供 POST /api/sessions/{id}/upload 路由调用。
// 与 filesend 不同，上传不参与 mux 控制消息链，只消费流式 HTTP 请求 body。
func (r *SessionRouter) SetFileUploadService(svc *fileupload.Service) {
	r.transfers.SetFileUploadService(svc)
}

// SetAgentNotificationDispatcher 显式注册 agent_notification.ack。
// 首版按单设备处理，deviceID 留空（与 Dispatcher.Notify 的 deviceID="" 一致）。
func (r *SessionRouter) SetAgentNotificationDispatcher(d *agentnotify.Dispatcher) {
	r.controls.Register(agentnotify.TypeAgentAck, func(_ context.Context, source MuxSession, msg map[string]any) {
		if d != nil {
			if eventID, _ := msg["event_id"].(string); eventID != "" {
				clientID := ""
				if r.fileSend != nil {
					clientID = r.fileSend.ClientIDForSender(source)
				}
				d.HandleAck(clientID, eventID)
			}
		}
	})
}

// RouteOpenWithControl 与 RouteOpen 相同，但在 mux 分支额外返回重建出的 mux session
// 作为 filesend.ControlSender，供 relay 代理侧注册为设备级控制通道。非 mux 分支 ctrl 为 nil。
func (r *SessionRouter) RouteOpenWithControl(
	ctx context.Context,
	socket session.Socket,
	path string,
	protocols []string,
) (func(), filesend.ControlSender, error) {
	clean := cleanPath(path)
	switch {
	case clean == "/ws/sessions":
		if r.muxServe != nil && hasProtocol(protocols, protocol.MuxSubprotocol) {
			muxSession := r.muxServe(socket, &MuxServeOpts{
				OnOpen:    r.OpenOwnedLogicalChannel,
				OnControl: r.controls.Dispatch,
				Logger:    r.logger,
			})
			start := func() {
				if r.fileSend != nil {
					defer r.fileSend.UnregisterSenderInstance(muxSession)
				}
				defer socket.Close()
				_ = muxSession.Run(ctx)
			}
			return start, muxSession, nil
		}
		return nil, nil, fmt.Errorf("%s requires %s", clean, protocol.MuxSubprotocol)

	default:
		return nil, nil, fmt.Errorf("unknown path: %s", path)
	}
}

// OpenLogicalChannel 把 Android mux channel 直接路由到 manager 或 Terminal Runtime handler。
// sink 只能向当前 channel 写帧，因此这里不需要构造虚拟请求、
// 虚拟响应、Socket.Read 队列或任何 WebSocket 对象。
func (r *SessionRouter) OpenLogicalChannel(
	ctx context.Context,
	sink session.ChannelFrameSink,
	path string,
	protocols []string,
) (session.LogicalChannelHandler, error) {
	return r.channels.Open(ctx, sink, path, protocols)
}

// OpenOwnedLogicalChannel 与 OpenLogicalChannel 相同，但 screen channel 会携带
// Android DeviceConnection 生命周期内稳定的 owner key，用于跨物理 Mux 原子接管旧 handler。
func (r *SessionRouter) OpenOwnedLogicalChannel(
	ctx context.Context,
	sink session.ChannelFrameSink,
	path string,
	protocols []string,
	ownerKey string,
) (session.LogicalChannelHandler, error) {
	return r.channels.OpenOwned(ctx, sink, path, protocols, ownerKey)
}

// RouteHTTP 处理 session CRUD 的 HTTP 请求代理（供 relay agent 使用）。
func (r *SessionRouter) RouteHTTP(method string, rawPath string, body []byte) (int, []byte, error) {
	return r.sessionHTTP.Route(method, rawPath, body)
}

// Manager 返回内部的 session.Manager。
func (r *SessionRouter) Manager() *session.Manager {
	return r.manager
}

// RouteHTTPv2 处理需要流式响应的 HTTP 请求（例如文件下载/发送）。
// header 用于提取授权信息（例如 transfer_token），可为 nil。
func (r *SessionRouter) RouteHTTPv2(method string, rawPath string, header http.Header, body io.Reader) (*HTTPResult, error) {
	return r.transfers.Route(method, rawPath, header, body)
}

// --- helpers ---

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
}
