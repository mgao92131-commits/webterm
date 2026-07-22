package direct

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/agentrouter"
	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// webSocketReadLimit 是单条 Direct WebSocket 消息的读取上限（16 MiB），与
// Relay Agent 物理连接的读限保持一致。
const webSocketReadLimit = 16 << 20

// loginBodyLimit 限制登录请求体大小，避免恶意大 body 耗尽内存。
const loginBodyLimit = 1 << 20

// sessionBodyLimit 限制 session CRUD 请求体大小（创建会话只携带很小的 cwd
// JSON）；上传走独立的流式路径，不受此限制。
const sessionBodyLimit = 1 << 20

// Server 是 Direct 模式的 HTTP/WebSocket 接入层。
//
// 职责：监听 HTTP、登录认证、Cookie 校验、WebSocket Accept、HTTP 请求转发与
// 优雅退出。不重新实现 Mux、终端帧、Session CRUD 或文件传输——这些全部复用
// 经 agentrouter 装配的 SessionRouter。
type Server struct {
	cfg     config.DirectConfig
	app     *app.App
	router  *application.SessionRouter
	auth    *Authenticator
	limiter *LoginLimiter

	authenticate func(username, password string) (string, bool)
	wsMu         sync.Mutex
	connections  map[*websocket.Conn]struct{}
	closing      bool
}

// New 创建 Direct Server。SessionRouter 的完整装配（Mux、控制消息、文件传输、
// 通知）由 agentrouter 统一提供，与 Relay Client 共享同一份逻辑。
func New(cfg config.DirectConfig, appInstance *app.App) *Server {
	return &Server{
		cfg:         cfg,
		app:         appInstance,
		router:      agentrouter.New(appInstance, "direct"),
		auth:        NewAuthenticator(cfg.Username, cfg.Password),
		limiter:     NewLoginLimiter(),
		connections: make(map[*websocket.Conn]struct{}),
	}
}

// ListenAndServe 监听 Direct 地址并服务 HTTP/WebSocket，直到 ctx 取消。
// 端口绑定失败会直接返回错误，使 Agent 进程退出（Direct 模式无法降级）。
// 实现 runtime.Runner 接口。
func (s *Server) ListenAndServe(ctx context.Context) error {
	listener, err := net.Listen("tcp", s.cfg.Addr)
	if err != nil {
		return err
	}
	return s.serve(ctx, listener)
}

// serve 在已绑定的 listener 上服务，直到 ctx 取消后优雅退出。拆分出来便于测试
// 注入自己的 listener（127.0.0.1:0）以获得确定的端口并验证优雅退出。
func (s *Server) serve(ctx context.Context, listener net.Listener) error {
	s.app.Log("info", "direct", "direct server listening addr="+listener.Addr().String())
	runtimeCtx, cancelRuntime := context.WithCancel(context.Background())
	defer cancelRuntime()
	defer s.closeWebSockets()

	// ReadHeaderTimeout 缓解 slowloris；不设较短的 WriteTimeout，因为文件发送/下载
	// 可能是长时间流式响应。WebSocket 连接被劫持后不受 IdleTimeout 影响。
	server := &http.Server{
		Handler:           s.routes(),
		BaseContext:       func(net.Listener) context.Context { return runtimeCtx },
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    64 << 10,
	}
	server.RegisterOnShutdown(s.closeWebSockets)
	serveErr := make(chan error, 1)
	go func() {
		err := server.Serve(listener)
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
		serveErr <- err
	}()

	select {
	case <-ctx.Done():
		// 先发 WebSocket close frame，再取消 Handler context，避免普通 context
		// 取消路径抢先以 EOF 结束客户端连接。
		s.closeWebSockets()
		cancelRuntime()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
		return ctx.Err()
	case err := <-serveErr:
		return err
	}
}

// routes 注册 Direct HTTP 路由。登录/刷新公开；me、session、文件与 WebSocket
// 均需通过 Cookie 认证。ServeMux 会优先匹配更具体的路径，因此 "/" 兜底不会
// 抢占 /api/auth/* 与 /ws/sessions。
func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/auth/login", s.handleLogin)
	mux.HandleFunc("/api/auth/refresh", s.handleRefresh)
	mux.HandleFunc("/api/auth/me", s.requireAuth(s.handleMe))
	mux.HandleFunc("/ws/sessions", s.requireAuth(s.handleSessionsWS))
	mux.HandleFunc("/", s.requireAuth(s.handleAPI))
	return mux
}

// handleLogin 处理 POST /api/auth/login。请求体复用 Android 现有格式
// {"username","password"}；成功签发 HttpOnly Cookie。失败只返回通用错误，
// 不泄露账户是否存在等细节，日志也不记录密码与 Token。
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	// 登录失败限流：被封禁时返回与密码错误相同的通用错误，不泄露限流状态。
	ip := clientIP(r)
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, loginBodyLimit))
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "请求体无效")
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		s.writeError(w, http.StatusBadRequest, "请求体无效")
		return
	}
	if !s.limiter.BeginAttempt(ip) {
		s.writeError(w, http.StatusUnauthorized, "账户或密码错误")
		return
	}
	authenticate := s.authenticate
	if authenticate == nil {
		authenticate = s.auth.Authenticate
	}
	token, ok := authenticate(req.Username, req.Password)
	if !ok {
		s.app.Log("warn", "direct", "direct login failed")
		s.writeError(w, http.StatusUnauthorized, "账户或密码错误")
		return
	}
	s.limiter.RecordSuccess(ip)
	setAuthCookie(w, token, s.auth.ttl)
	s.app.Log("info", "direct", "direct login ok")
	s.writeJSON(w, http.StatusOK, map[string]any{"ok": true, "username": s.cfg.Username})
}

// clientIP 从请求远端地址提取 IP（Direct 面向局域网，RemoteAddr 即客户端 IP）。
func clientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// handleRefresh 处理 POST /api/auth/refresh：校验当前 Cookie 并旋转出新 Token。
// 旧 Token 无效时清除 Cookie 并返回 401，由 Android 用保存的账户密码重新登录。
func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	newToken, ok := s.auth.Rotate(tokenFromRequest(r))
	if !ok {
		clearAuthCookie(w)
		s.writeError(w, http.StatusUnauthorized, "会话已过期")
		return
	}
	setAuthCookie(w, newToken, s.auth.ttl)
	s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// handleMe 返回当前登录账户信息（已通过 requireAuth 校验）。
func (s *Server) handleMe(w http.ResponseWriter, _ *http.Request) {
	s.writeJSON(w, http.StatusOK, map[string]any{"username": s.cfg.Username})
}

// handleAPI 将 session CRUD 与文件传输请求转发给 SessionRouter，路由划分与
// Relay HTTPProxy 保持一致：
//
//   - 上传（POST /api/sessions/{id}/upload）与文件发送（/api/file-send/）走
//     RouteHTTPv2（流式 body/响应）；
//   - 其余 session CRUD 走 RouteHTTP，直接回写路由返回的状态码（含 404/405/204），
//     而不是把路由错误折叠成 502。
func (s *Server) handleAPI(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	if isUploadRequest(r.Method, path) || strings.HasPrefix(path, "/api/file-send/") {
		result, err := s.router.RouteHTTPv2(r.Method, path, r.Header, r.Body)
		if err != nil {
			s.app.Log("warn", "direct", "transfer route failed: "+err.Error())
			s.writeError(w, http.StatusBadGateway, "会话请求失败")
			return
		}
		writeHTTPResult(w, result)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, sessionBodyLimit))
	if err != nil {
		s.writeError(w, http.StatusBadRequest, "请求体无效")
		return
	}
	status, payload, routeErr := s.router.RouteHTTP(r.Method, path, body)
	if routeErr != nil && status == 0 {
		status = http.StatusInternalServerError
	}
	if status == 0 {
		status = http.StatusOK
	}
	if routeErr != nil && len(payload) == 0 {
		payload, _ = json.Marshal(map[string]string{"error": routeErr.Error()})
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if len(payload) > 0 {
		_, _ = w.Write(payload)
	}
}

// isUploadRequest 判断是否为终端文件上传路由（POST /api/sessions/{id}/upload），
// 与 relay 的判定保持一致。
func isUploadRequest(method, path string) bool {
	return method == http.MethodPost &&
		strings.HasPrefix(path, "/api/sessions/") &&
		strings.HasSuffix(path, "/upload")
}

// handleSessionsWS 处理 GET /ws/sessions：验证 Cookie（requireAuth 已完成）后
// Accept WebSocket、确认 webterm.mux.v1 子协议、包装 session.Socket，并交由
// SessionRouter.RouteOpenWithControl 启动 Mux 会话。不修改任何帧格式。
func (s *Server) handleSessionsWS(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols:    []string{protocol.MuxSubprotocol},
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		return
	}
	if !s.registerWebSocket(conn) {
		_ = conn.Close(websocket.StatusGoingAway, "agent shutting down")
		return
	}
	defer s.unregisterWebSocket(conn)
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != protocol.MuxSubprotocol {
		conn.Close(websocket.StatusPolicyViolation, "unsupported subprotocol")
		return
	}
	conn.SetReadLimit(webSocketReadLimit)

	socket := session.NewWebSocketAdapter(conn)
	start, _, err := s.router.RouteOpenWithControl(r.Context(), socket, "/ws/sessions", []string{protocol.MuxSubprotocol})
	if err != nil {
		conn.Close(websocket.StatusInternalError, err.Error())
		return
	}
	if start != nil {
		start()
	}
}

func (s *Server) registerWebSocket(conn *websocket.Conn) bool {
	s.wsMu.Lock()
	defer s.wsMu.Unlock()
	if s.closing {
		return false
	}
	s.connections[conn] = struct{}{}
	return true
}

func (s *Server) unregisterWebSocket(conn *websocket.Conn) {
	s.wsMu.Lock()
	delete(s.connections, conn)
	s.wsMu.Unlock()
}

func (s *Server) closeWebSockets() {
	s.wsMu.Lock()
	if s.closing {
		s.wsMu.Unlock()
		return
	}
	s.closing = true
	connections := make([]*websocket.Conn, 0, len(s.connections))
	for conn := range s.connections {
		connections = append(connections, conn)
	}
	s.wsMu.Unlock()

	for _, conn := range connections {
		_ = conn.Close(websocket.StatusGoingAway, "agent shutting down")
	}
}

// requireAuth 校验会话 Cookie；无效返回 401，不进入后续处理。
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.auth.Validate(tokenFromRequest(r)) {
			s.writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, r)
	}
}

// writeHTTPResult 把 SessionRouter 的 HTTPResult 回写给客户端：复制响应头，
// 再写出流式 Body 或字节 Data。
func writeHTTPResult(w http.ResponseWriter, result *application.HTTPResult) {
	header := w.Header()
	for key, values := range result.Header {
		for _, value := range values {
			header.Add(key, value)
		}
	}
	w.WriteHeader(result.StatusCode)
	if result.Body != nil {
		defer result.Body.Close()
		_, _ = io.Copy(w, result.Body)
		return
	}
	if len(result.Data) > 0 {
		_, _ = w.Write(result.Data)
	}
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

func (s *Server) writeError(w http.ResponseWriter, status int, message string) {
	s.writeJSON(w, status, map[string]string{"error": message})
}
