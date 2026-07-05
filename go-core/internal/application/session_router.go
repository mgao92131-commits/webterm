package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// MuxServeFunc 是 mux.Serve 的函数签名，用于避免 application → mux 的循环依赖。
// 调用方（direct server / relay agent）负责传入 mux.Serve 的实际实现。
type MuxServeFunc func(conn session.Socket, opts *MuxServeOpts) MuxSession

// MuxSession 是 mux.Session 的接口抽象。
type MuxSession interface {
	Run(ctx context.Context) error
}

// MuxServeOpts 是 mux.ServeOpts 的类型投影。
type MuxServeOpts struct {
	OnOpen    func(ctx context.Context, vs MuxVirtualSocket, path string, protocols []string) (func(), error)
	OnControl func(ctx context.Context, msg map[string]any)
	Logger    *logs.Logger
}

// MuxVirtualSocket 是 mux.VirtualSocket 的接口抽象。
type MuxVirtualSocket interface {
	Read(ctx context.Context) (session.MessageType, []byte, error)
	Write(ctx context.Context, messageType session.MessageType, data []byte) error
	Close() error
}

// SessionRouter 统一 session 路径分发和 CRUD 逻辑，
// 供 direct server 和 relay agent 共用，消除重复。
type SessionRouter struct {
	manager  *session.Manager
	muxServe MuxServeFunc // 可选：用于 mux 子协议包装
	logger   *logs.Logger
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
	return &SessionRouter{manager: manager, muxServe: muxServe, logger: log}
}

// RouteOpen 根据 WebSocket 路径和子协议创建 ManagerClient 或终端 Client。
// 返回 start 函数由调用方在握手 ack 完成后调用。
func (r *SessionRouter) RouteOpen(
	ctx context.Context,
	socket session.Socket,
	path string,
	protocols []string,
) (func(), error) {
	clean := cleanPath(path)
	switch {
	case clean == "/ws/sessions":
		if r.muxServe != nil && hasProtocol(protocols, protocol.MuxSubprotocol) {
			muxSession := r.muxServe(socket, &MuxServeOpts{
				OnOpen: func(ctx context.Context, vs MuxVirtualSocket, p string, protos []string) (func(), error) {
					return r.RouteOpen(ctx, vs, p, protos)
				},
				Logger: r.logger,
			})
			return func() {
				defer socket.Close()
				_ = muxSession.Run(ctx)
			}, nil
		}
		mc := session.NewManagerClient(socket, r.logger)
		return func() { go mc.Run(ctx, r.manager) }, nil

	case strings.HasPrefix(clean, "/ws/sessions/"):
		id := strings.TrimPrefix(clean, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := r.manager.Get(id)
		if !ok {
			return nil, fmt.Errorf("session %s not found", id)
		}
		mode := session.ClientModeFromProtocol(selectProtocol(protocols))
		client := session.NewClient(socket, terminal, mode, r.logger)
		return func() { go client.Run(ctx) }, nil

	default:
		return nil, fmt.Errorf("unknown path: %s", path)
	}
}

// RouteHTTP 处理 session CRUD 的 HTTP 请求代理（供 relay agent 使用）。
func (r *SessionRouter) RouteHTTP(method string, rawPath string, body []byte) (int, []byte, error) {
	path := cleanPath(rawPath)

	if method == http.MethodGet && path == "/api/sessions" {
		return marshalStatus(http.StatusOK, r.manager.List())
	}
	if method == http.MethodPost && path == "/api/sessions" {
		var req struct {
			Name string `json:"name"`
			CWD  string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &req)
		}
		terminal, err := r.manager.Create(req.Name, req.CWD)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		return marshalStatus(http.StatusCreated, terminal.Info())
	}
	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		id, _ = url.PathUnescape(id)
		if method == http.MethodPatch {
			var req struct {
				Name string `json:"name"`
			}
			if len(body) > 0 {
				_ = json.Unmarshal(body, &req)
			}
			terminal, ok := r.manager.Rename(id, req.Name)
			if !ok {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return marshalStatus(http.StatusOK, terminal.Info())
		}
		if method == http.MethodDelete {
			if !r.manager.Close(id) {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return http.StatusNoContent, []byte{}, nil
		}
	}
	return http.StatusNotFound, nil, errors.New("not found")
}

// Manager 返回内部的 session.Manager（供 P2P 等组件需要直接访问时使用）。
func (r *SessionRouter) Manager() *session.Manager {
	return r.manager
}

// --- helpers ---

func hasProtocol(protocols []string, target string) bool {
	for _, p := range protocols {
		if p == target {
			return true
		}
	}
	return false
}

func selectProtocol(protocols []string) string {
	for _, p := range protocols {
		if p == protocol.ScreenSubprotocol {
			return protocol.ScreenSubprotocol
		}
	}
	for _, p := range protocols {
		if p == protocol.BinarySubprotocol {
			return protocol.BinarySubprotocol
		}
	}
	for _, p := range protocols {
		if p == protocol.JSONSubprotocol {
			return protocol.JSONSubprotocol
		}
	}
	return protocol.JSONSubprotocol
}

func cleanPath(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return raw
}

func marshalStatus(status int, value any) (int, []byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return http.StatusInternalServerError, nil, err
	}
	return status, payload, nil
}
