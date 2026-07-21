package localipc

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

	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// Application is the small Agent surface exposed to the local IPC server.
// Keeping it as an interface avoids making the transport package depend on
// app, which itself uses localipc endpoint helpers.
type Application interface {
	Sessions() *session.Manager
	FileSendService() *filesend.Service
	AgentNotificationDispatcher() *agentnotify.Dispatcher
	Log(level, source, message string)
	// DiagnosticsSummary 返回运行中 Agent 的只读诊断快照（已脱敏）。
	DiagnosticsSummary() map[string]any
	// ExportDiagnostics 生成诊断包并返回实际输出路径；exportDir 为空时使用默认位置。
	// 实现必须保证失败时返回 error 而非 panic，且不影响 Agent 主循环。
	ExportDiagnostics(exportDir string) (string, error)
}

type Server struct {
	app      Application
	endpoint string

	mu       sync.Mutex
	listener net.Listener
	wg       sync.WaitGroup
}

func NewServer(endpoint string, application Application) *Server {
	return &Server{app: application, endpoint: endpoint}
}

func (s *Server) ListenAndServe(ctx context.Context) error {
	ln, err := Listen(s.endpoint)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.listener = ln
	s.mu.Unlock()
	defer ln.Close()
	s.app.Log("info", "localipc", fmt.Sprintf("listening endpoint=%s", s.endpoint))

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
			s.app.Log("warn", "localipc", fmt.Sprintf("accept failed: %v", err))
			continue
		}
		s.wg.Add(1)
		go s.handleConn(ctx, conn)
	}
}

func (s *Server) handleConn(ctx context.Context, conn net.Conn) {
	defer s.wg.Done()
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	if !scanner.Scan() {
		return
	}
	var envelope Envelope
	if err := json.Unmarshal(scanner.Bytes(), &envelope); err != nil {
		s.writeError(conn, "", "invalid_envelope")
		return
	}
	if err := validateEnvelope(envelope); err != nil {
		s.writeError(conn, envelope.RequestID, err.Error())
		return
	}
	_ = conn.SetReadDeadline(time.Time{})
	s.dispatch(ctx, conn, envelope)
}

func validateEnvelope(envelope Envelope) error {
	if envelope.Version != Version {
		return fmt.Errorf("unsupported_version")
	}
	if envelope.Kind != KindCommand && envelope.Kind != KindEvent {
		return fmt.Errorf("invalid_kind")
	}
	if envelope.Type == "" {
		return fmt.Errorf("missing_type")
	}
	return nil
}

func (s *Server) dispatch(ctx context.Context, conn net.Conn, envelope Envelope) {
	switch envelope.Type {
	case TypeSend:
		if envelope.Kind != KindCommand {
			s.writeError(conn, envelope.RequestID, "invalid_kind")
			return
		}
		var request SendRequest
		if err := DecodePayload(envelope.Payload, &request); err != nil {
			s.writeError(conn, envelope.RequestID, "invalid_payload")
			return
		}
		s.handleSend(conn, envelope.RequestID, request)
	case TypeDevices:
		if envelope.Kind != KindCommand {
			s.writeError(conn, envelope.RequestID, "invalid_kind")
			return
		}
		var request DevicesRequest
		if err := DecodePayload(envelope.Payload, &request); err != nil {
			s.writeError(conn, envelope.RequestID, "invalid_payload")
			return
		}
		s.writePayload(conn, envelope.RequestID, TypeDevices, protocol.CLIResponse{
			Kind: "response", Type: "device_list", Status: "complete",
			Devices: s.app.FileSendService().ListClients(request.OnlineOnly),
		})
	case TypeNotify:
		if envelope.Kind != KindEvent {
			s.writeError(conn, envelope.RequestID, "invalid_kind")
			return
		}
		var notification Notification
		if err := DecodePayload(envelope.Payload, &notification); err != nil {
			s.writeError(conn, envelope.RequestID, "invalid_payload")
			return
		}
		if err := s.handleNotification(ctx, notification); err != nil {
			s.writeError(conn, envelope.RequestID, err.Error())
			return
		}
		s.writePayload(conn, envelope.RequestID, TypeNotify, map[string]string{"status": "ok"})
	case TypeSessionUpdate:
		if envelope.Kind != KindEvent {
			s.writeError(conn, envelope.RequestID, "invalid_kind")
			return
		}
		var update SessionUpdate
		if err := DecodePayload(envelope.Payload, &update); err != nil {
			s.writeError(conn, envelope.RequestID, "invalid_payload")
			return
		}
		if err := s.handleSessionUpdate(update); err != nil {
			s.writeError(conn, envelope.RequestID, err.Error())
			return
		}
		s.writePayload(conn, envelope.RequestID, TypeSessionUpdate, map[string]string{"status": "ok"})
	case TypeDiagnostics:
		if envelope.Kind != KindCommand {
			s.writeError(conn, envelope.RequestID, "invalid_kind")
			return
		}
		var request DiagnosticsRequest
		if err := DecodePayload(envelope.Payload, &request); err != nil {
			s.writeError(conn, envelope.RequestID, "invalid_payload")
			return
		}
		s.handleDiagnostics(conn, envelope.RequestID, request)
	default:
		s.writeError(conn, envelope.RequestID, "unknown_type")
	}
}

func (s *Server) resolveTerminal(sessionID string, pid int) (*session.TerminalSession, string, error) {
	if sessionID == "" && pid > 0 {
		resolved, err := s.app.Sessions().ResolveSessionForPID(pid)
		if err != nil {
			return nil, "", fmt.Errorf("session_not_found")
		}
		sessionID = resolved
	}
	if sessionID == "" {
		return nil, "", fmt.Errorf("session_not_found")
	}
	terminal, ok := s.app.Sessions().Get(sessionID)
	if !ok {
		return nil, "", fmt.Errorf("session_not_found")
	}
	return terminal, sessionID, nil
}

func (s *Server) handleNotification(ctx context.Context, notification Notification) error {
	if notification.Message == "" {
		return fmt.Errorf("missing_message")
	}
	if notification.Importance != agentnotify.ImportanceAlert &&
		notification.Importance != agentnotify.ImportanceNormal &&
		notification.Importance != agentnotify.ImportanceQuiet {
		return fmt.Errorf("invalid_importance")
	}
	terminal, sessionID, err := s.resolveTerminal(notification.SessionID, notification.PID)
	if err != nil {
		return err
	}
	terminal.ApplyNotification(notification.Importance, notification.Message, notification.Source, notification.Timestamp)
	if notification.Importance == agentnotify.ImportanceQuiet {
		return nil
	}
	title := strings.TrimSpace(terminal.Info().TermTitle)
	if title == "" {
		title = notification.Source
	}
	if title == "" {
		title = "WebTerm"
	}
	_, err = s.app.AgentNotificationDispatcher().Notify(ctx, "", sessionID, notification.Importance, title, notification.Message, notification.Source)
	if err != nil {
		s.app.Log("warn", "localipc", fmt.Sprintf("agent_notification dispatch failed: %v", err))
		return fmt.Errorf("notification_not_delivered")
	}
	return nil
}

func (s *Server) handleSessionUpdate(update SessionUpdate) error {
	if update.ShellState == "" && update.CWD == "" && update.LastInput == "" {
		return fmt.Errorf("empty_session_update")
	}
	if update.ShellState != "" && update.ShellState != "running" && update.ShellState != "prompt" && update.ShellState != "unknown" {
		return fmt.Errorf("invalid_shell_state")
	}
	if update.InputKind != "" && update.InputKind != "shell" && update.InputKind != "agent_prompt" && update.InputKind != "agent_tool" {
		return fmt.Errorf("invalid_input_kind")
	}
	terminal, _, err := s.resolveTerminal(update.SessionID, update.PID)
	if err != nil {
		return err
	}
	terminal.ApplySessionUpdate(update.ShellState, update.CWD, update.LastInput, update.InputKind, update.Timestamp)
	return nil
}

// handleDiagnostics 处理诊断摘要查询与诊断包导出。本方法运行在连接 goroutine 内，
// 额外 recover 以确保诊断导出中的任何意外都不会影响 Agent 主循环。
func (s *Server) handleDiagnostics(conn net.Conn, requestID string, request DiagnosticsRequest) {
	defer func() {
		if recovered := recover(); recovered != nil {
			s.app.Log("warn", "localipc", fmt.Sprintf("diagnostics panic: %v", recovered))
			s.writeError(conn, requestID, "diagnostics_internal_error")
		}
	}()
	switch request.Action {
	case DiagnosticsActionSummary, "":
		s.writePayload(conn, requestID, TypeDiagnostics, DiagnosticsResponse{
			Action:  DiagnosticsActionSummary,
			Summary: s.app.DiagnosticsSummary(),
		})
	case DiagnosticsActionExport:
		path, err := s.app.ExportDiagnostics(request.ExportPath)
		if err != nil {
			s.app.Log("warn", "localipc", fmt.Sprintf("diagnostics export failed: %v", err))
			s.writeError(conn, requestID, "export_failed")
			return
		}
		s.writePayload(conn, requestID, TypeDiagnostics, DiagnosticsResponse{
			Action:     DiagnosticsActionExport,
			ExportPath: path,
		})
	default:
		s.writeError(conn, requestID, "invalid_action")
	}
}

func (s *Server) handleSend(conn net.Conn, requestID string, request SendRequest) {
	fail := func(code string) {
		s.writePayload(conn, requestID, TypeSend, protocol.CLIResponse{Kind: "response", Type: "file_send_status", Status: string(filesend.StatusFailed), Error: code})
	}
	if request.FilePath == "" {
		fail("missing_file_path")
		return
	}
	absPath := request.FilePath
	if !filepath.IsAbs(absPath) && request.CWD != "" {
		absPath = filepath.Join(request.CWD, absPath)
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
	target, err := svc.SelectClient(request.Device, "file_receive")
	if err != nil {
		fail(err.Error())
		return
	}
	task, err := svc.CreateTask(filesend.CreateTaskOptions{DeviceID: target.ID, Path: absPath, FileName: info.Name(), Size: info.Size(), SHA256: sha256Hex})
	if err != nil {
		fail("create_task_failed")
		return
	}
	s.writePayload(conn, requestID, TypeSend, protocol.CLIResponse{Kind: "response", Type: "file_send_status", Status: string(filesend.StatusOffered), DownloadID: task.ID, FilePath: task.FileName, TotalBytes: task.Size, TargetDevice: &target})
	if err := svc.SendOffer(context.Background(), task); err != nil {
		task.SetFailed("device_not_connected")
		s.writePayload(conn, requestID, TypeSend, protocol.CLIResponse{Kind: "response", Type: "file_send_status", Status: string(filesend.StatusFailed), DownloadID: task.ID, FilePath: task.FileName, Error: err.Error()})
		svc.Remove(task.ID)
		return
	}
	for response := range task.StateChan {
		s.writePayload(conn, requestID, TypeSend, response)
		if filesend.Status(response.Status).IsTerminal() {
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

func (s *Server) writeError(conn net.Conn, requestID, code string) {
	s.writeEnvelope(conn, Envelope{Version: Version, Kind: KindResponse, Type: "error", RequestID: requestID, Error: code})
}

func (s *Server) writePayload(conn net.Conn, requestID, typ string, payload any) {
	raw, err := json.Marshal(payload)
	if err != nil {
		s.writeError(conn, requestID, "encode_response_failed")
		return
	}
	s.writeEnvelope(conn, Envelope{Version: Version, Kind: KindResponse, Type: typ, RequestID: requestID, Payload: raw})
}

func (s *Server) writeEnvelope(conn net.Conn, envelope Envelope) {
	data, err := json.Marshal(envelope)
	if err != nil {
		return
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, _ = conn.Write(append(data, '\n'))
}
