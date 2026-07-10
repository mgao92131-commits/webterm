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
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/protocol"
)

// Server 监听本地 Unix Socket，接收 webterm CLI / shell hook 上报的事件与命令。
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
				s.wg.Wait()
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

	// Close connection when context is canceled to unblock scanner.Scan().
	connClosed := make(chan struct{})
	defer close(connClosed)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-connClosed:
		}
	}()

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

		// 通过 kind 字段区分 CLICommand 与 legacy HookEvent
		var env struct {
			Kind string `json:"kind"`
		}
		if err := json.Unmarshal(line, &env); err == nil && env.Kind == "command" {
			var cmd protocol.CLICommand
			if err := json.Unmarshal(line, &cmd); err != nil {
				s.app.Log("warn", "hook", fmt.Sprintf("invalid command json: %v", err))
				continue
			}
			// 命令处理可能持续数分钟，清除读超时
			_ = conn.SetReadDeadline(time.Time{})
			s.handleCommand(conn, cmd)
			// 一个连接只处理一个命令
			return
		}

		// legacy HookEvent（无 kind 字段或 kind 不为 command）
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
	if ev.Timestamp == 0 {
		ev.Timestamp = time.Now().Unix()
	}

	sessionID := ev.SessionID
	if sessionID == "" && ev.PID > 0 {
		resolved, err := s.app.Sessions().ResolveSessionForPID(ev.PID)
		if err != nil {
			return fmt.Errorf("cannot resolve session for pid %d: %w", ev.PID, err)
		}
		sessionID = resolved
	}
	if sessionID == "" {
		return fmt.Errorf("missing session_id")
	}

	terminal, ok := s.app.Sessions().Get(sessionID)
	if !ok {
		return fmt.Errorf("session %s not found", sessionID)
	}
	terminal.ApplyHookEvent(ev)
	return nil
}

func (s *Server) handleCommand(conn net.Conn, cmd protocol.CLICommand) {
	if cmd.Type == "" {
		writeResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "error",
			Status: "failed",
			Error:  "missing_command_type",
		})
		return
	}

	sessionID := cmd.SessionID
	if sessionID == "" && cmd.PID > 0 {
		resolved, err := s.app.Sessions().ResolveSessionForPID(cmd.PID)
		if err != nil {
			writeResponse(conn, protocol.CLIResponse{
				Kind:   "response",
				Type:   cmd.Type + "_status",
				Status: "failed",
				Error:  "session_not_found",
			})
			return
		}
		sessionID = resolved
	}
	if sessionID == "" {
		writeResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   cmd.Type + "_status",
			Status: "failed",
			Error:  "session_not_found",
		})
		return
	}

	terminal, ok := s.app.Sessions().Get(sessionID)
	if !ok {
		writeResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   cmd.Type + "_status",
			Status: "failed",
			Error:  "session_not_found",
		})
		return
	}

	if cmd.Type == "send" {
		s.handleSendCommand(conn, cmd, sessionID)
		return
	}

	terminal.HandleCLICommand(conn, cmd)
}

func (s *Server) handleSendCommand(conn net.Conn, cmd protocol.CLICommand, sessionID string) {
	fail := func(errCode string) {
		writeResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "file_send_status",
			Status: string(filesend.StatusFailed),
			Error:  errCode,
		})
	}

	if cmd.FilePath == "" {
		fail("missing_file_path")
		return
	}
	absPath := cmd.FilePath
	if !filepath.IsAbs(absPath) && cmd.CWD != "" {
		absPath = filepath.Join(cmd.CWD, absPath)
	}
	info, err := os.Stat(absPath)
	if err != nil || info.IsDir() {
		fail("file_not_found")
		return
	}

	svc := s.app.FileSendService()
	task, err := svc.CreateTask(filesend.CreateTaskOptions{
		SessionID: sessionID,
		Path:      absPath,
		FileName:  info.Name(),
		Size:      info.Size(),
	})
	if err != nil {
		fail("create_task_failed")
		return
	}

	// 先发一个 offered 响应，让 CLI 立即进入等待态。
	writeResponse(conn, protocol.CLIResponse{
		Kind:       "response",
		Type:       "file_send_status",
		Status:     string(filesend.StatusOffered),
		DownloadID: task.ID,
		FilePath:   task.FileName,
		TotalBytes: task.Size,
	})

	if err := svc.SendOffer(context.Background(), task); err != nil {
		// 里程碑 B：设备级 sender 尚未注册（Android WebTermDeviceService 在里程碑 C 接入）。
		s.app.Log("warn", "hook", fmt.Sprintf("send offer not delivered: %v", err))
		task.SetFailed("device_not_connected")
		writeResponse(conn, protocol.CLIResponse{
			Kind:       "response",
			Type:       "file_send_status",
			Status:     string(filesend.StatusFailed),
			DownloadID: task.ID,
			FilePath:   task.FileName,
			Error:      "device_not_connected",
		})
		svc.Remove(task.ID)
		return
	}

	// 转发 file_send.* 状态流直到终态。
	for resp := range task.StateChan {
		if resp.Timestamp == 0 {
			resp.Timestamp = time.Now().Unix()
		}
		writeResponse(conn, resp)
		if filesend.Status(resp.Status).IsTerminal() {
			return
		}
	}
}

func writeResponse(conn net.Conn, resp protocol.CLIResponse) {
	if resp.Timestamp == 0 {
		resp.Timestamp = time.Now().Unix()
	}
	data, err := json.Marshal(resp)
	if err != nil {
		return
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, _ = conn.Write(append(data, '\n'))
}
