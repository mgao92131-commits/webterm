package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

type Server struct {
	addr       string
	app        *app.App
	configPath string
	runtime    RuntimeController
	server     *http.Server
}

type RuntimeController interface {
	Restart(context.Context) error
	Stop(context.Context) error
}

const (
	controlReadTimeout        = 30 * time.Second
	controlIdleTimeout        = 120 * time.Second
	controlMaxRequestBodySize = 1 << 20 // 1 MiB
)

func New(addr string, app *app.App) *Server {
	return NewWithConfigPath(addr, app, "")
}

func NewWithConfigPath(addr string, app *app.App, configPath string) *Server {
	return NewWithRuntime(addr, app, configPath, nil)
}

func NewWithRuntime(addr string, app *app.App, configPath string, runtime RuntimeController) *Server {
	control := &Server{addr: addr, app: app, configPath: configPath, runtime: runtime}
	mux := http.NewServeMux()
	mux.HandleFunc("/control/status", control.handleStatus)
	mux.HandleFunc("/control/config", control.handleConfig)
	mux.HandleFunc("/control/restart", control.handleRestart)
	mux.HandleFunc("/control/stop", control.handleStop)
	mux.HandleFunc("/control/logs", control.handleLogs)
	mux.HandleFunc("/control/logs/stream", control.handleLogsStream)
	mux.HandleFunc("/control/connection/test", control.handleConnectionTest)
	mux.HandleFunc("/control/sessions", control.handleSessions)
	mux.HandleFunc("/control/sessions/", control.handleSessionDetail)
	mux.HandleFunc("/control/traffic", control.handleTraffic)
	control.server = &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       controlReadTimeout,
		IdleTimeout:       controlIdleTimeout,
		MaxHeaderBytes:    1 << 20,
	}
	return control
}

func (control *Server) ListenAndServe(ctx context.Context) error {
	listener, err := net.Listen("tcp", control.addr)
	if err != nil {
		return err
	}
	errCh := make(chan error, 1)
	go func() {
		errCh <- control.server.Serve(listener)
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		err := control.server.Shutdown(shutdownCtx)
		if err != nil {
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

func (control *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, control.app.Status())
}

func (control *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, control.app.Config().Redacted())
	case http.MethodPut:
		var next config.Config
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, controlMaxRequestBodySize)).Decode(&next); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		control.applyConfig(w, config.MergeEditable(control.app.Config(), next))
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (control *Server) applyConfig(w http.ResponseWriter, cfg config.Config) {
	if err := config.Save(control.configPath, cfg); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	control.app.UpdateConfig(cfg)
	restarted := false
	if control.runtime != nil {
		if err := control.runtime.Restart(context.Background()); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		restarted = true
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"config":          cfg.Redacted(),
		"saved":           control.configPath != "",
		"configPath":      control.configPath,
		"restarted":       restarted,
		"restartRequired": control.app.Status().RestartRequired,
	})
}

func (control *Server) handleRestart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if control.runtime == nil {
		writeError(w, http.StatusNotImplemented, "runtime supervisor is not configured")
		return
	}
	if err := control.runtime.Restart(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"restarted": true, "status": control.app.Status()})
}

func (control *Server) handleStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if control.runtime == nil {
		writeError(w, http.StatusNotImplemented, "runtime supervisor is not configured")
		return
	}
	if err := control.runtime.Stop(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"stopped": true, "status": control.app.Status()})
}

func (control *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if value, err := strconv.Atoi(raw); err == nil && value > 0 {
			limit = value
		}
	}
	writeJSON(w, http.StatusOK, control.app.Logs().Recent(limit))
}

func (control *Server) handleLogsStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Connection", "keep-alive")

	for _, entry := range control.app.Logs().Recent(100) {
		writeSSE(w, entry)
	}
	flusher.Flush()

	events, cancel := control.app.Logs().Subscribe(64)
	defer cancel()
	for {
		select {
		case <-r.Context().Done():
			return
		case entry, ok := <-events:
			if !ok {
				return
			}
			writeSSE(w, entry)
			flusher.Flush()
		}
	}
}

func (control *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, control.app.Sessions().List())
}

func (control *Server) handleSessionDetail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/control/sessions/")
	parts := strings.Split(path, "/")
	if len(parts) < 2 || parts[0] == "" || parts[1] != "screen" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	terminal, ok := control.app.Sessions().Get(parts[0])
	if !ok {
		writeError(w, http.StatusNotFound, "session not found")
		return
	}
	if len(parts) == 2 {
		writeJSON(w, http.StatusOK, terminal.ProjectedScreenSnapshot())
		return
	}
	if len(parts) == 3 && parts[2] == "projected" {
		writeJSON(w, http.StatusOK, terminal.ProjectedScreenSnapshot())
		return
	}
	writeError(w, http.StatusNotFound, "not found")
}

func (control *Server) handleTraffic(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"sessions": control.app.Sessions().TrafficSnapshots(),
	})
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

func writeSSE(w http.ResponseWriter, value any) {
	bytes, err := json.Marshal(value)
	if err != nil {
		return
	}
	_, _ = fmt.Fprintf(w, "event: log\ndata: %s\n\n", bytes)
}
