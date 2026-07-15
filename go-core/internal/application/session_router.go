package application

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

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

// MuxServeOpts 是 mux.ServeOpts 的类型投影。
type MuxServeOpts struct {
	OnOpen    func(ctx context.Context, vs MuxVirtualSocket, path string, protocols []string) (func(), error)
	OnControl func(ctx context.Context, source MuxSession, msg map[string]any)
	Logger    *logs.Logger
}

// MuxVirtualSocket 是 mux.VirtualSocket 的接口抽象。
type MuxVirtualSocket interface {
	Read(ctx context.Context) (session.MessageType, []byte, error)
	Write(ctx context.Context, messageType session.MessageType, data []byte) error
	Close() error
}

// SessionRouter 统一 session 路径分发和 CRUD 逻辑，
// 供 Relay Agent 统一处理 mux channel。
type SessionRouter struct {
	manager    *session.Manager
	muxServe   MuxServeFunc // 可选：用于 mux 子协议包装
	onControl  func(ctx context.Context, source MuxSession, msg map[string]any)
	fileSend   *filesend.Service
	fileUpload *fileupload.Service
	logger     *logs.Logger
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
func (r *SessionRouter) SetControlHandler(onControl func(ctx context.Context, source MuxSession, msg map[string]any)) {
	r.onControl = onControl
}

// SetFileSendService 注入 FileSendService，并把它链接到 mux 控制消息处理链前端：
// file_send.* 消息优先交给 Service.HandleControl，未处理的消息继续传递给此前注册的处理器。
func (r *SessionRouter) SetFileSendService(svc *filesend.Service) {
	r.fileSend = svc
	prev := r.onControl
	r.onControl = func(ctx context.Context, source MuxSession, msg map[string]any) {
		if svc != nil && svc.HandleControlFrom(ctx, source, msg) {
			return
		}
		if prev != nil {
			prev(ctx, source, msg)
		}
	}
}

// SetFileUploadService 注入 FileUploadService，供 POST /api/sessions/{id}/upload 路由调用。
// 与 filesend 不同，上传不参与 mux 控制消息链，只消费流式 HTTP 请求 body。
func (r *SessionRouter) SetFileUploadService(svc *fileupload.Service) {
	r.fileUpload = svc
}

// SetAgentNotificationDispatcher 注入 AgentNotificationDispatcher 并链接到控制链前端：
// agent_notification.ack 交给 Dispatcher.HandleAck 清理 pending，其余消息继续传递。
// 首版按单设备处理，deviceID 留空（与 Dispatcher.Notify 的 deviceID="" 一致）。
func (r *SessionRouter) SetAgentNotificationDispatcher(d *agentnotify.Dispatcher) {
	prev := r.onControl
	r.onControl = func(ctx context.Context, source MuxSession, msg map[string]any) {
		if d != nil {
			if typ, _ := msg["type"].(string); typ == agentnotify.TypeAgentAck {
				if eventID, _ := msg["event_id"].(string); eventID != "" {
					clientID := ""
					if r.fileSend != nil {
						clientID = r.fileSend.ClientIDForSender(source)
					}
					d.HandleAck(clientID, eventID)
				}
				return
			}
		}
		if prev != nil {
			prev(ctx, source, msg)
		}
	}
}

// RouteOpen 根据 WebSocket 路径和子协议创建 ManagerClient 或终端 Client。
// 返回 start 函数由调用方在握手 ack 完成后调用。
func (r *SessionRouter) RouteOpen(
	ctx context.Context,
	socket session.Socket,
	path string,
	protocols []string,
) (func(), error) {
	start, _, err := r.RouteOpenWithControl(ctx, socket, path, protocols)
	return start, err
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
				OnOpen: func(ctx context.Context, vs MuxVirtualSocket, p string, protos []string) (func(), error) {
					return r.RouteOpen(ctx, vs, p, protos)
				},
				OnControl: r.onControl,
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
		mc := session.NewManagerClient(socket, r.logger)
		return func() { go mc.Run(ctx, r.manager) }, nil, nil

	case strings.HasPrefix(clean, "/ws/sessions/"):
		// c1 只接受 Go 权威屏幕协议；设备级文件/通知控制仍走外层 mux control。
		if !hasProtocol(protocols, protocol.ScreenSubprotocol) {
			return nil, nil, fmt.Errorf("terminal sessions require %s", protocol.ScreenSubprotocol)
		}
		id := strings.TrimPrefix(clean, "/ws/sessions/")
		id, _ = url.PathUnescape(id)
		terminal, ok := r.manager.Get(id)
		if !ok {
			return nil, nil, fmt.Errorf("session %s not found", id)
		}
		client := session.NewClient(socket, terminal, session.ClientModeScreen, r.logger)
		return func() { go client.Run(ctx) }, nil, nil

	default:
		return nil, nil, fmt.Errorf("unknown path: %s", path)
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
			CWD string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &req)
		}
		terminal, err := r.manager.Create(req.CWD)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		return marshalStatus(http.StatusCreated, terminal.Info())
	}
	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		id, _ = url.PathUnescape(id)
		if method == http.MethodPatch {
			return http.StatusMethodNotAllowed, nil, errors.New("method not allowed")
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

// Manager 返回内部的 session.Manager。
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

// RouteHTTPv2 处理需要流式响应的 HTTP 请求（例如文件下载/发送）。
// header 用于提取授权信息（例如 transfer_token），可为 nil。
func (r *SessionRouter) RouteHTTPv2(method string, rawPath string, header http.Header, body io.Reader) (*HTTPResult, error) {
	path := cleanPath(rawPath)

	// POST /api/sessions/{sessionId}/upload：流式上传，body 直接透传给 fileupload.Service，
	// 禁止 io.ReadAll；业务错误按 5.2 表返回正常 JSON 响应，不能转成 StreamError。
	if method == http.MethodPost && strings.HasPrefix(path, "/api/sessions/") {
		if id, ok := strings.CutSuffix(strings.TrimPrefix(path, "/api/sessions/"), "/upload"); ok {
			id, _ = url.PathUnescape(id)
			return r.routeUpload(header, body, id), nil
		}
	}

	if strings.HasPrefix(path, "/api/file-send/") {
		if r.fileSend == nil {
			return &HTTPResult{
				StatusCode: http.StatusServiceUnavailable,
				Data:       []byte("file-send service unavailable"),
			}, nil
		}
		transferID := strings.TrimPrefix(path, "/api/file-send/")
		transferID, _, _ = strings.Cut(transferID, "?")
		token := filesend.TokenFromRequest(header)
		res := r.fileSend.HandleFileSendRequest(transferID, token)
		return &HTTPResult{
			StatusCode: res.StatusCode,
			Header:     res.Header,
			Body:       res.Body,
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

// routeUpload 处理 POST /api/sessions/{sessionId}/upload。
// DeclaredSize 取值优先级：X-File-Size > Content-Length > -1（未知）；
// 最终大小判据以实际 body 字节数为准（由 fileupload.Service 在落盘后校验）。
func (r *SessionRouter) routeUpload(header http.Header, body io.Reader, sessionID string) *HTTPResult {
	if r.fileUpload == nil {
		return uploadErrorResult(http.StatusServiceUnavailable, fileupload.CodeInternalError, "上传服务不可用")
	}
	// Android 端 OkHttp 拒绝非 ASCII header 值，统一改用 X-File-Name-B64
	//（文件名字节数组的 URL-safe Base64，带填充）；同时兼容原始 X-File-Name。
	fileName := header.Get("X-File-Name")
	if fileName == "" {
		if encoded := header.Get("X-File-Name-B64"); encoded != "" {
			decoded, err := base64.URLEncoding.DecodeString(encoded)
			if err != nil {
				return uploadErrorResult(http.StatusBadRequest, fileupload.CodeInvalidFileName, "X-File-Name-B64 解码失败")
			}
			fileName = string(decoded)
		}
	}
	if fileName == "" {
		return uploadErrorResult(http.StatusBadRequest, fileupload.CodeInvalidFileName, "缺少 X-File-Name")
	}
	declaredSize := int64(-1)
	if sizeHeader := header.Get("X-File-Size"); sizeHeader != "" {
		size, err := strconv.ParseInt(sizeHeader, 10, 64)
		if err != nil || size < 0 {
			return uploadErrorResult(http.StatusBadRequest, fileupload.CodeSizeMismatch, "X-File-Size 非法")
		}
		declaredSize = size
	} else if contentLength := header.Get("Content-Length"); contentLength != "" {
		// 注意：net/http 服务端请求中 Content-Length 不在 Header map 里，
		// Relay gateway 需要显式转发 r.ContentLength。
		if size, err := strconv.ParseInt(contentLength, 10, 64); err == nil && size >= 0 {
			declaredSize = size
		}
	}
	if body == nil {
		body = http.NoBody
	}
	result, err := r.fileUpload.Upload(context.Background(), fileupload.Request{
		SessionID:    sessionID,
		FileName:     fileName,
		DeclaredSize: declaredSize,
		Body:         body,
	})
	if err != nil {
		code := fileupload.CodeOf(err)
		message := "上传失败"
		var uploadErr *fileupload.Error
		if errors.As(err, &uploadErr) {
			message = uploadErr.Message
		}
		return uploadErrorResult(uploadHTTPStatus(code), code, message)
	}
	data, marshalErr := json.Marshal(result)
	if marshalErr != nil {
		return uploadErrorResult(http.StatusInternalServerError, fileupload.CodeInternalError, "序列化上传结果失败")
	}
	return &HTTPResult{
		StatusCode: http.StatusOK,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
	}
}

// uploadHTTPStatus 按计划 5.2 表把上传业务错误码映射为 HTTP 状态码。
// UPLOAD_CONFLICT 不在表中：同 session 已有活跃上传，按资源冲突返回 409。
func uploadHTTPStatus(code fileupload.Code) int {
	switch code {
	case fileupload.CodeSessionNotFound:
		return http.StatusNotFound
	case fileupload.CodeSessionCWDUnavailable, fileupload.CodeUploadDirectoryInvalid, fileupload.CodeUploadConflict:
		return http.StatusConflict
	case fileupload.CodeUploadDirectoryNotWritable:
		return http.StatusForbidden
	case fileupload.CodeInvalidFileName, fileupload.CodeSizeMismatch, fileupload.CodeTransferInterrupted:
		return http.StatusBadRequest
	case fileupload.CodeFileTooLarge:
		return http.StatusRequestEntityTooLarge
	case fileupload.CodeInsufficientDiskSpace:
		return http.StatusInsufficientStorage
	default:
		return http.StatusInternalServerError
	}
}

// uploadErrorResult 构造 {"code":"...","message":"..."} 形状的业务错误响应。
func uploadErrorResult(status int, code fileupload.Code, message string) *HTTPResult {
	data, err := json.Marshal(map[string]string{"code": string(code), "message": message})
	if err != nil {
		data = []byte(`{"code":"INTERNAL_ERROR","message":"上传失败"}`)
		status = http.StatusInternalServerError
	}
	return &HTTPResult{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
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
