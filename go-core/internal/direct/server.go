package direct

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

const cookieName = "webterm_token"

const (
	directIdleTimeout        = 120 * time.Second
	directMaxRequestBodySize = 1 << 20 // 1 MiB
)

type Server struct {
	cfg        config.DirectConfig
	app        *app.App
	token      string
	staticRoot string
	server     *http.Server
}

func New(cfg config.DirectConfig, application *app.App) *Server {
	direct := &Server{
		cfg:        cfg,
		app:        application,
		token:      randomToken(),
		staticRoot: resolveStaticRoot(cfg.WebRoot),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", direct.route)
	direct.server = &http.Server{
		Addr:              cfg.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       directIdleTimeout,
		MaxHeaderBytes:    1 << 20,
	}
	return direct
}

func (direct *Server) ListenAndServe(ctx context.Context) error {
	listener, err := net.Listen("tcp", direct.cfg.Addr)
	if err != nil {
		return err
	}
	direct.app.SetDirectListening(true)
	defer direct.app.SetDirectListening(false)

	errCh := make(chan error, 1)
	go func() {
		errCh <- direct.server.Serve(listener)
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := direct.server.Shutdown(shutdownCtx); err != nil {
			return err
		}
		return ctx.Err()
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (direct *Server) route(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	if path == "" {
		path = "/"
	}

	if r.Method == http.MethodPost && (path == "/api/login" || path == "/api/auth/login") {
		direct.handleLogin(w, r)
		return
	}

	if strings.HasPrefix(path, "/ws/") {
		if !direct.authenticated(r) {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		direct.routeWebSocket(w, r, path)
		return
	}

	if strings.HasPrefix(path, "/api/") {
		if !direct.authenticated(r) {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		direct.routeAPI(w, r, path)
		return
	}

	serveStatic(w, r, direct.staticRoot)
}

func (direct *Server) routeWebSocket(w http.ResponseWriter, r *http.Request, path string) {
	if path != "/ws/sessions" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		return
	}
	conn.SetReadLimit(8 << 20)
	if conn.Subprotocol() != protocol.MuxSubprotocol {
		_ = conn.Close(websocket.StatusPolicyViolation, "mux subprotocol required")
		return
	}
	router := direct.sessionRouter()
	sess := mux.Serve(session.NewWebSocketAdapter(conn), &mux.ServeOpts{
		OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) (func(), error) {
			return mux.OpenSessionOrManager(ctx, router, vs, p, protos)
		},
		Logger: direct.app.Logs(),
	})
	sess.Run(r.Context())
}

func (direct *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Username string `json:"username"`
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, directMaxRequestBodySize)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	username := body.Username
	if username == "" {
		username = body.Email
	}
	if !direct.verify(username, body.Password) {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     cookieName,
		Value:    direct.token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"username": direct.cfg.User,
		"email":    direct.cfg.User,
		"role":     "admin",
		"mode":     config.ModeDirect,
	})
}

func (direct *Server) routeAPI(w http.ResponseWriter, r *http.Request, path string) {
	if r.Method == http.MethodGet && (path == "/api/me" || path == "/api/auth/me") {
		writeJSON(w, http.StatusOK, map[string]any{
			"id":       1,
			"username": direct.cfg.User,
			"role":     "admin",
			"mode":     config.ModeDirect,
		})
		return
	}

	if path == "/api/sessions" {
		direct.routeSessions(w, r)
		return
	}

	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		direct.routeSession(w, r, id)
		return
	}

	writeError(w, http.StatusNotFound, "not found")
}

func (direct *Server) routeSessions(w http.ResponseWriter, r *http.Request) {
	router := direct.sessionRouter()
	status, body, err := router.RouteHTTP(r.Method, r.URL.Path, readRequestBody(w, r))
	if err != nil {
		writeError(w, status, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func (direct *Server) sessionRouter() *application.SessionRouter {
	router := application.NewSessionRouter(direct.app.Sessions(), direct.app.Logs())
	router.SetAgentHooks(direct.app.SocketPath(), direct.app.AgentHookScriptPath())
	return router
}

func readRequestBody(w http.ResponseWriter, r *http.Request) []byte {
	body, _ := io.ReadAll(http.MaxBytesReader(w, r.Body, directMaxRequestBodySize))
	return body
}

func (direct *Server) routeSession(w http.ResponseWriter, r *http.Request, id string) {
	switch r.Method {
	case http.MethodPatch:
		var body struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, directMaxRequestBodySize)).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		terminal, ok := direct.app.Sessions().Rename(id, body.Name)
		if !ok {
			writeError(w, http.StatusNotFound, "session not found")
			return
		}
		writeJSON(w, http.StatusOK, terminal.Info())
	case http.MethodDelete:
		if !direct.app.Sessions().Close(id) {
			writeError(w, http.StatusNotFound, "session not found")
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusNoContent)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (direct *Server) authenticated(r *http.Request) bool {
	cookie, err := r.Cookie(cookieName)
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(direct.token)) == 1
}

func (direct *Server) verify(username string, password string) bool {
	if direct.cfg.Password == "" {
		return false
	}
	if subtle.ConstantTimeCompare([]byte(username), []byte(direct.cfg.User)) != 1 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(password), []byte(direct.cfg.Password)) == 1
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func randomToken() string {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return time.Now().UTC().Format("20060102150405.000000000")
	}
	return hex.EncodeToString(bytes[:])
}
