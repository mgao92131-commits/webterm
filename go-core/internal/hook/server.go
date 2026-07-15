package hook

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
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
	s.dispatchAgentNotification(sessionID, terminal, ev)
	return nil
}

// dispatchAgentNotification 把 agent_event Hook 事件以设备级 agent_notification 下发到 Android。
// 仅把 alert|normal 的 agent_event 下发到设备级通知通道；quiet 只更新 session 状态，不下发。
// 标题取终端会话 TermTitle，回退 source、再回退事件类型。
// 首版 deviceID 留空，依赖底层单设备回退；多设备精确路由留待后续。失败仅记录，不影响原有 MSG_HOOK 路径。
func (s *Server) dispatchAgentNotification(sessionID string, terminal *session.TerminalSession, ev protocol.HookEvent) {
	dispatcher := s.app.AgentNotificationDispatcher()
	if dispatcher == nil {
		return
	}
	importance := ev.Importance
	if importance == "" || importance == "quiet" {
		return
	}
	title := strings.TrimSpace(terminal.Info().TermTitle)
	if title == "" {
		title = ev.Source
	}
	if title == "" {
		title = ev.Type
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := dispatcher.Notify(ctx, "", sessionID, importance, title, ev.Message, ev.Source); err != nil {
		s.app.Log("warn", "hook", fmt.Sprintf("agent_notification dispatch failed: %v", err))
	}
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

	// send 是设备级命令，与终端会话无关，不做 session 解析。
	if cmd.Type == "send" {
		s.handleSendCommand(conn, cmd)
		return
	}
	if cmd.Type == "devices" {
		s.handleDevicesCommand(conn, cmd)
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

	terminal.HandleCLICommand(conn, cmd)
}

func (s *Server) handleDevicesCommand(conn net.Conn, cmd protocol.CLICommand) {
	writeResponse(conn, protocol.CLIResponse{
		Kind: "response", Type: "device_list", Status: "complete",
		Devices: s.app.FileSendService().ListClients(cmd.OnlineOnly),
	})
}

func (s *Server) handleSendCommand(conn net.Conn, cmd protocol.CLICommand) {
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
	if err != nil {
		fail("file_not_found")
		return
	}
	if !info.Mode().IsRegular() {
		fail("file_not_regular")
		return
	}
	sha256Hex, err := fileSHA256(absPath)
	if err != nil {
		fail("hash_failed")
		return
	}

	svc := s.app.FileSendService()
	target, err := svc.SelectClient(cmd.Device, "file_receive")
	if err != nil {
		fail(err.Error())
		return
	}
	task, err := svc.CreateTask(filesend.CreateTaskOptions{
		DeviceID: target.ID,
		Path:     absPath,
		FileName: info.Name(),
		Size:     info.Size(),
		SHA256:   sha256Hex,
	})
	if err != nil {
		fail("create_task_failed")
		return
	}

	// 先发一个 offered 响应，让 CLI 立即进入等待态。
	writeResponse(conn, protocol.CLIResponse{
		Kind:         "response",
		Type:         "file_send_status",
		Status:       string(filesend.StatusOffered),
		DownloadID:   task.ID,
		FilePath:     task.FileName,
		TotalBytes:   task.Size,
		TargetDevice: &target,
	})

	if err := svc.SendOffer(context.Background(), task); err != nil {
		s.app.Log("warn", "hook", fmt.Sprintf("send offer not delivered: %v", err))
		task.SetFailed("device_not_connected")
		writeResponse(conn, protocol.CLIResponse{
			Kind:       "response",
			Type:       "file_send_status",
			Status:     string(filesend.StatusFailed),
			DownloadID: task.ID,
			FilePath:   task.FileName,
			Error:      err.Error(),
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

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
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
