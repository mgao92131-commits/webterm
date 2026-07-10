package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
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
	manager   *session.Manager
	muxServe  MuxServeFunc // 可选：用于 mux 子协议包装
	onControl func(ctx context.Context, msg map[string]any)
	logger    *logs.Logger
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

// SetControlHandler 设置 mux 设备级控制消息处理器。
// 用于 file_send.*、agent_notification 等不经过虚拟通道的控制消息。
func (r *SessionRouter) SetControlHandler(onControl func(ctx context.Context, msg map[string]any)) {
	r.onControl = onControl
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
				OnControl: r.onControl,
				Logger:    r.logger,
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

// HTTPResult 是 RouteHTTPv2 的流式返回结果。
type HTTPResult struct {
	StatusCode int
	Header     http.Header
	Body       io.ReadCloser
	Data       []byte // 小文件兜底：当 Body 为 nil 时使用 Data
}

// RouteHTTPv2 处理需要流式响应的 HTTP 请求（例如文件下载）。
func (r *SessionRouter) RouteHTTPv2(method string, rawPath string, body io.Reader) (*HTTPResult, error) {
	path := cleanPath(rawPath)

	if strings.HasPrefix(path, "/api/fs/download") {
		parsed, err := url.Parse(rawPath)
		if err != nil {
			return nil, err
		}
		return r.handleDownload(parsed.RawQuery)
	}

	if strings.HasPrefix(path, "/api/file-send/") {
		// Phase 0 占位：FileSendService 尚未接入，返回 501。
		return &HTTPResult{
			StatusCode: http.StatusNotImplemented,
			Data:       []byte("file-send service not yet implemented"),
		}, nil
	}

	// 其他路由回退到 RouteHTTP
	status, data, err := r.RouteHTTP(method, rawPath, nil)
	if err != nil {
		return nil, err
	}
	return &HTTPResult{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
	}, nil
}

func (r *SessionRouter) handleDownload(query string) (*HTTPResult, error) {
	params, err := url.ParseQuery(query)
	if err != nil {
		return nil, err
	}
	downloadID := params.Get("downloadId")
	if downloadID == "" {
		return nil, errors.New("missing downloadId")
	}

	task, ok := r.manager.GetDownloadTask(downloadID)
	if !ok {
		return &HTTPResult{
			StatusCode: http.StatusGone,
			Data:       []byte("download task not found"),
		}, nil
	}
	if task.SessionID != params.Get("sessionId") {
		r.notifyDownloadStatus(task, "failed", "session mismatch")
		return &HTTPResult{
			StatusCode: http.StatusGone,
			Data:       []byte("download task not found"),
		}, nil
	}

	file, err := os.Open(task.Path)
	if err != nil {
		r.notifyDownloadStatus(task, "failed", err.Error())
		return &HTTPResult{
			StatusCode: http.StatusForbidden,
			Data:       []byte(err.Error()),
		}, nil
	}

	header := http.Header{}
	header.Set("Content-Type", "application/octet-stream")
	header.Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, task.FileName))
	header.Set("Content-Length", strconv.FormatInt(task.Size, 10))

	r.notifyDownloadStatus(task, "started", "")

	return &HTTPResult{
		StatusCode: http.StatusOK,
		Header:     header,
		Body:       &downloadNotifyReader{ReadCloser: file, task: task},
	}, nil
}

// downloadNotifyReader 在读取到 EOF 时自动发送 complete 状态。
type downloadNotifyReader struct {
	io.ReadCloser
	task *session.DownloadTask
	done bool
}

func (r *downloadNotifyReader) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	if err == io.EOF && !r.done {
		r.done = true
		notifyDownloadTaskComplete(r.task)
	}
	return n, err
}

func notifyDownloadTaskComplete(task *session.DownloadTask) {
	if task == nil {
		return
	}
	select {
	case task.StateChan <- protocol.CLIResponse{
		Kind:             "response",
		Type:             "download_status",
		Status:           "complete",
		DownloadID:       task.ID,
		BytesTransferred: task.Size,
		TotalBytes:       task.Size,
	}:
	default:
	}
}

func (r *SessionRouter) notifyDownloadStatus(task *session.DownloadTask, status, errMsg string) {
	if task == nil {
		return
	}
	var resp protocol.CLIResponse
	if status == "failed" {
		resp = protocol.CLIResponse{
			Kind:       "response",
			Type:       "download_status",
			Status:     "failed",
			DownloadID: task.ID,
			Error:      errMsg,
		}
	} else {
		resp = protocol.CLIResponse{
			Kind:       "response",
			Type:       "download_status",
			Status:     status,
			DownloadID: task.ID,
			TotalBytes: task.Size,
		}
	}
	select {
	case task.StateChan <- resp:
	default:
	}
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
