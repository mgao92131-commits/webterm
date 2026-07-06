package hook

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/protocol"
)

// Server 监听本地 Unix Socket，接收 webterm CLI / shell hook 上报的事件。
type Server struct {
	app        *app.App
	socketPath string

	mu       sync.Mutex
	listener net.Listener
	wg       sync.WaitGroup
}

func New(socketPath string, app *app.App) *Server {
	return &Server{
		app:        app,
		socketPath: socketPath,
	}
}

func (s *Server) ListenAndServe(ctx context.Context) error {
	dir := filepath.Dir(s.socketPath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("create socket directory: %w", err)
	}
	if _, err := os.Stat(s.socketPath); err == nil {
		if isSocketActive(s.socketPath) {
			return fmt.Errorf("socket already in use: %s", s.socketPath)
		}
		_ = os.Remove(s.socketPath)
	}

	ln, err := net.Listen("unix", s.socketPath)
	if err != nil {
		return fmt.Errorf("listen unix socket %s: %w", s.socketPath, err)
	}
	_ = os.Chmod(s.socketPath, 0o600)

	s.mu.Lock()
	s.listener = ln
	s.mu.Unlock()
	defer ln.Close()

	s.app.Log("info", "hook", fmt.Sprintf("listening socket=%s", s.socketPath))

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			s.app.Log("warn", "hook", fmt.Sprintf("accept failed: %v", err))
			continue
		}
		s.wg.Add(1)
		go s.handleConn(ctx, conn)
	}
}

func (s *Server) handleConn(ctx context.Context, conn net.Conn) {
	defer s.wg.Done()
	defer conn.Close()

	if err := conn.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
		return
	}

	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		if err := conn.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
			return
		}
		line := scanner.Bytes()
		var ev protocol.HookEvent
		if err := json.Unmarshal(line, &ev); err != nil {
			s.app.Log("warn", "hook", fmt.Sprintf("invalid json: %v", err))
			continue
		}
		if err := s.dispatch(ev); err != nil {
			s.app.Log("warn", "hook", err.Error())
		}
	}
}

func isSocketActive(path string) bool {
	conn, err := net.DialTimeout("unix", path, 200*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func (s *Server) dispatch(ev protocol.HookEvent) error {
	if ev.Type == "" {
		return fmt.Errorf("missing event type")
	}
	if ev.SessionID == "" {
		return fmt.Errorf("missing session_id")
	}
	if ev.Timestamp == 0 {
		ev.Timestamp = time.Now().Unix()
	}

	terminal, ok := s.app.Sessions().Get(ev.SessionID)
	if !ok {
		return fmt.Errorf("session %s not found", ev.SessionID)
	}
	terminal.ApplyHookEvent(ev)
	return nil
}
