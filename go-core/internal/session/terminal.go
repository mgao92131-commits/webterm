package session

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"webterm/go-core/internal/fsops"
	"webterm/go-core/internal/infrastructure/pty"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/terminalsession"
)

const (
	DefaultCols = 100
	DefaultRows = 30
)

type TerminalOptions struct {
	ID            string
	Name          string
	CWD           string
	Command       string
	Args          []string
	Cols          int
	Rows          int
	Env           map[string]string
	OnTitle       func()
	OnInfoChanged func()
}

type TerminalSession struct {
	mu              sync.RWMutex
	id              string
	instance        string
	name            string
	termTitle       string
	cwd             string
	liveCwd         string
	command         string
	status          string
	shellState      string
	lastInput       *LastInput
	notification    *Notification
	cols            int
	rows            int
	createdAt       time.Time
	activeAt        time.Time
	ring            *EventRing
	runtime         *terminalsession.Runtime
	process         *pty.Process
	shellPid        int
	ttyPath         string
	clients         map[*Client]struct{}
	onTitleChanged  func()
	onInfoChanged   func()
	titleChanged    bool
	manager         *Manager
}

type Notification struct {
	Level     string `json:"level"`
	Message   string `json:"message"`
	Source    string `json:"source,omitempty"`
	Timestamp int64  `json:"timestamp"`
}

func NewTerminalSession(options TerminalOptions) (*TerminalSession, error) {
	now := time.Now().UTC()
	cols := options.Cols
	if cols <= 0 {
		cols = DefaultCols
	}
	rows := options.Rows
	if rows <= 0 {
		rows = DefaultRows
	}

	process, err := pty.Start(pty.Options{
		CWD:     options.CWD,
		Command: options.Command,
		Args:    options.Args,
		Cols:    cols,
		Rows:    rows,
		Env:     options.Env,
	})
	if err != nil {
		return nil, err
	}

	terminal := &TerminalSession{
		id:             options.ID,
		instance:       randomID(),
		name:           normalize(options.Name),
		cwd:            process.CWD(),
		command:        process.Command(),
		status:         StatusRunning,
		cols:           cols,
		rows:           rows,
		createdAt:      now,
		activeAt:       now,
		ring:           NewEventRing(0, 0),
		process:        process,
		shellPid:       process.PID(),
		ttyPath:        process.TTYPath(),
		clients:        make(map[*Client]struct{}),
		onTitleChanged: options.OnTitle,
		onInfoChanged:  options.OnInfoChanged,
	}
	terminal.runtime = terminalsession.NewRuntime(
		terminal.id,
		process.PTY(),
		rows,
		cols,
		terminalsession.WithOnOutput(func(data []byte) {
			frame := terminal.PushOutput(data)
			terminal.broadcastOutput(frame)
		}),
		terminalsession.WithOnTitle(func(title string) {
			terminal.mu.Lock()
			terminal.termTitle = title
			terminal.titleChanged = true
			onTitleChanged := terminal.onTitleChanged
			onInfoChanged := terminal.onInfoChanged
			terminal.mu.Unlock()
			if onTitleChanged != nil {
				onTitleChanged()
			}
			if onInfoChanged != nil {
				onInfoChanged()
			}
		}),
		terminalsession.WithOnWorkingDirectory(func(cwd string) {
			terminal.mu.Lock()
			terminal.liveCwd = cwd
			terminal.touchLocked()
			onTitleChanged := terminal.onTitleChanged
			onInfoChanged := terminal.onInfoChanged
			terminal.mu.Unlock()
			if onTitleChanged != nil {
				onTitleChanged()
			}
			if onInfoChanged != nil {
				onInfoChanged()
			}
		}),
		terminalsession.WithPTYResizer(process.Resize),
	)
	go terminal.waitLoop()
	return terminal, nil
}

func (terminal *TerminalSession) ID() string {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.id
}

func (terminal *TerminalSession) ShellPID() int {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.shellPid
}

func (terminal *TerminalSession) TTYPath() string {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.ttyPath
}

func (terminal *TerminalSession) Info() Info {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	cwd := terminal.cwd
	if terminal.liveCwd != "" {
		cwd = terminal.liveCwd
	}
	instanceID := terminal.instance
	if terminal.runtime != nil {
		instanceID = terminal.runtime.Info().InstanceID
	}
	return Info{
		ID:                terminal.id,
		InstanceID:        instanceID,
		Name:              terminal.name,
		TermTitle:         terminal.termTitle,
		DisplayTitle:      displayTitle(terminal.name, terminal.termTitle),
		CWD:               cwd,
		RecentInputLines:  []string{},
		RecentInputHidden: false,
		Command:           terminal.command,
		Status:            terminal.status,
		ShellState:        terminal.shellState,
		LastCommand:       terminal.lastInputText(),
		LastInputKind:     terminal.lastInputKind(),
		TTY:               terminal.ttyPath,
		Notification:      terminal.notification,
		Clients:           len(terminal.clients),
		Cols:              terminal.cols,
		Rows:              terminal.rows,
		CreatedAt:         terminal.createdAt,
		LastActiveAt:      terminal.activeAt,
	}
}

func (terminal *TerminalSession) lastInputText() string {
	if terminal.lastInput == nil {
		return ""
	}
	return terminal.lastInput.Text
}

func (terminal *TerminalSession) lastInputKind() string {
	if terminal.lastInput == nil {
		return ""
	}
	return terminal.lastInput.Kind
}

func (terminal *TerminalSession) Rename(name string) {
	terminal.mu.Lock()
	defer terminal.mu.Unlock()
	terminal.name = normalize(name)
	terminal.touchLocked()
}

func (terminal *TerminalSession) Close() {
	terminal.mu.Lock()
	if terminal.status == StatusClosed {
		terminal.mu.Unlock()
		return
	}
	terminal.status = StatusClosed
	terminal.touchLocked()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.Unlock()

	if terminal.process != nil {
		_ = terminal.process.Close()
		terminal.process.Kill()
	}
	if terminal.runtime != nil {
		_ = terminal.runtime.Close()
	}
	for _, client := range clients {
		client.Close()
	}
}

func (terminal *TerminalSession) PushOutput(data []byte) EventFrame {
	terminal.mu.Lock()
	frame := terminal.pushOutputLocked(data)
	changed := terminal.titleChanged
	terminal.titleChanged = false
	onTitleChanged := terminal.onTitleChanged
	onInfoChanged := terminal.onInfoChanged
	terminal.mu.Unlock()

	if changed && onTitleChanged != nil {
		onTitleChanged()
	}
	if changed && onInfoChanged != nil {
		onInfoChanged()
	}
	return frame
}

func (terminal *TerminalSession) ReplayAfter(seq uint64) []EventFrame {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.ring.After(seq)
}

func (terminal *TerminalSession) CanReplayFrom(seq uint64) bool {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.ring.CanReplayFrom(seq)
}

func (terminal *TerminalSession) LatestSeq() uint64 {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.ring.LatestSeq()
}

func (terminal *TerminalSession) WriteInput(data []byte) error {
	terminal.mu.Lock()
	terminal.touchLocked()
	terminal.mu.Unlock()
	if terminal.process == nil {
		return errors.New("terminal has no process")
	}
	_, err := terminal.process.Write(data)
	return err
}

func (terminal *TerminalSession) Resize(cols int, rows int) error {
	if cols < 10 || rows < 5 {
		return nil
	}
	if cols > 500 {
		cols = 500
	}
	if rows > 200 {
		rows = 200
	}
	terminal.mu.Lock()
	terminal.cols = cols
	terminal.rows = rows
	if terminal.process != nil {
		terminal.process.Resize(cols, rows)
	}
	if terminal.runtime != nil {
		terminal.runtime.ResizeEngine(rows, cols)
	}
	terminal.touchLocked()
	terminal.mu.Unlock()
	return nil
}

func (terminal *TerminalSession) Attach(client *Client) {
	terminal.mu.Lock()
	terminal.clients[client] = struct{}{}
	terminal.touchLocked()
	terminal.mu.Unlock()
}

func (terminal *TerminalSession) Detach(client *Client) {
	terminal.mu.Lock()
	delete(terminal.clients, client)
	terminal.touchLocked()
	terminal.mu.Unlock()
}

// AttachScreenClient 把 screen protocol 客户端附加到权威 Runtime。
func (terminal *TerminalSession) AttachScreenClient(c *terminalsession.ScreenClient) {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt != nil {
		rt.AttachClient(c)
	}
}

// DetachScreenClient 从 Runtime 分离 screen protocol 客户端。
func (terminal *TerminalSession) DetachScreenClient(clientID string) {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt != nil {
		rt.DetachClient(clientID)
	}
}

// ScreenRuntime 返回底层 Runtime（screen client 集成用）。
func (terminal *TerminalSession) ScreenRuntime() *terminalsession.Runtime {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.runtime
}

// ProjectedScreenSnapshot exposes the authoritative screen-protocol frame for
// local diagnostics.
func (terminal *TerminalSession) ProjectedScreenSnapshot() any {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt == nil {
		return nil
	}
	return rt.ProjectedSnapshot()
}

// ProjectedInputTrace exposes metadata-only input diagnostics for the
// authoritative screen runtime.
func (terminal *TerminalSession) ProjectedInputTrace() []terminalsession.InputTrace {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt == nil {
		return nil
	}
	return rt.InputTraceSnapshot()
}

func (terminal *TerminalSession) RawPTYOutputSnapshot() terminalsession.RawPTYOutputSnapshot {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt == nil {
		return terminalsession.RawPTYOutputSnapshot{}
	}
	return rt.RawPTYOutputSnapshot()
}

func (terminal *TerminalSession) waitLoop() {
	code, _ := terminal.process.Wait()
	terminal.markClosed()
	terminal.broadcastExit(code)
}

// Note: pushOutputLocked must only be called while terminal.mu is held.
// Any caller invoking this method is responsible for checking and resetting
// the terminal.titleChanged flag (as done in PushOutput) to ensure title updates
// are properly broadcasted and titleChanged state does not leak.
func (terminal *TerminalSession) pushOutputLocked(data []byte) EventFrame {
	terminal.touchLocked()
	return terminal.ring.Push(data)
}

func (terminal *TerminalSession) markClosed() {
	terminal.mu.Lock()
	terminal.status = StatusClosed
	terminal.touchLocked()
	terminal.mu.Unlock()
}

func (terminal *TerminalSession) broadcastOutput(frame EventFrame) {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.SendOutput(frame)
	}
}

func (terminal *TerminalSession) broadcastExit(code int) {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.SendExit(code)
		client.Close()
	}
}

func (terminal *TerminalSession) ApplyHookEvent(ev protocol.HookEvent) {
	terminal.mu.Lock()
	if terminal.status == StatusClosed {
		terminal.mu.Unlock()
		return
	}
	switch ev.Type {
	case "notify":
		terminal.notification = &Notification{
			Level:     ev.Level,
			Message:   ev.Message,
			Source:    ev.Source,
			Timestamp: ev.Timestamp,
		}
		terminal.touchLocked()
	case "state":
		if ev.ShellState != "" {
			terminal.shellState = ev.ShellState
		}
		terminal.touchLocked()
	case "meta":
		if ev.CWD != "" {
			terminal.liveCwd = ev.CWD
		}
		if ev.LastCommand != "" {
			kind := ev.InputKind
			if kind == "" {
				kind = "shell"
			}
			terminal.lastInput = &LastInput{
				Kind:      kind,
				Text:      ev.LastCommand,
				Timestamp: ev.Timestamp,
			}
		}
		terminal.touchLocked()
	}
	onInfoChanged := terminal.onInfoChanged
	terminal.mu.Unlock()

	if onInfoChanged != nil {
		onInfoChanged()
	}

	if ev.Type == "notify" {
		terminal.broadcastHook(ev)
	} else {
		terminal.broadcastInfo()
	}
}

func (terminal *TerminalSession) broadcastInfo() {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.SendInfo()
	}
}

func (terminal *TerminalSession) broadcastHook(ev protocol.HookEvent) {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.SendHook(ev)
	}
}

// HandleCLICommand 处理 CLI 主动发起的命令请求。
func (terminal *TerminalSession) HandleCLICommand(conn net.Conn, cmd protocol.CLICommand) {
	switch cmd.Type {
	case "download":
		terminal.handleDownloadCommand(conn, cmd)
	default:
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   cmd.Type + "_status",
			Status: "failed",
			Error:  "unknown_command",
		})
	}
}

func (terminal *TerminalSession) handleDownloadCommand(conn net.Conn, cmd protocol.CLICommand) {
	terminal.mu.RLock()
	if terminal.status == StatusClosed {
		terminal.mu.RUnlock()
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "session_closed",
		})
		return
	}
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()

	if len(clients) == 0 {
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "android_not_connected",
		})
		return
	}

	targetPath, err := fsops.ResolveCLIPath(cmd.CWD, cmd.FilePath)
	if err != nil {
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "invalid_path",
		})
		return
	}

	info, err := os.Stat(targetPath)
	if err != nil {
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "file_not_found",
		})
		return
	}
	if !info.Mode().IsRegular() {
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "not_a_regular_file",
		})
		return
	}

	f, err := os.Open(targetPath)
	if err != nil {
		writeCLIResponse(conn, protocol.CLIResponse{
			Kind:   "response",
			Type:   "download_status",
			Status: "failed",
			Error:  "permission_denied",
		})
		return
	}
	_ = f.Close()

	downloadID := generateDownloadID()
	task := &DownloadTask{
		ID:        downloadID,
		SessionID: terminal.id,
		Path:      targetPath,
		FileName:  filepath.Base(targetPath),
		Size:      info.Size(),
		StateChan: make(chan protocol.CLIResponse, 32),
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(10 * time.Minute),
	}
	terminal.manager.AddDownloadTask(terminal.id, task)

	terminal.broadcastHook(protocol.HookEvent{
		Type:       "download",
		DownloadID: downloadID,
		SessionID:  terminal.id,
		FilePath:   task.FileName,
		FileName:   task.FileName,
		TotalBytes: task.Size,
		FileSize:   task.Size,
		Status:     "pending",
		Timestamp:  time.Now().Unix(),
	})

	writeCLIResponse(conn, protocol.CLIResponse{
		Kind:       "response",
		Type:       "download_status",
		Status:     "preparing",
		DownloadID: downloadID,
		FilePath:   task.FileName,
		TotalBytes: task.Size,
	})

	for {
		select {
		case event, ok := <-task.StateChan:
			if !ok {
				return
			}
			writeCLIResponse(conn, event)
			if event.Status == "complete" || event.Status == "failed" {
				terminal.manager.RemoveDownloadTask(downloadID)
				return
			}
		case <-time.After(10 * time.Minute):
			writeCLIResponse(conn, protocol.CLIResponse{
				Kind:   "response",
				Type:   "download_status",
				Status: "failed",
				Error:  "timeout",
			})
			terminal.manager.RemoveDownloadTask(downloadID)
			return
		}
	}
}

// OnDownloadProgress 处理 Android 回传的下载进度。
func (terminal *TerminalSession) OnDownloadProgress(downloadID string, current, total int64) {
	task, ok := terminal.manager.PeekDownloadTask(downloadID)
	if !ok {
		return
	}
	select {
	case task.StateChan <- protocol.CLIResponse{
		Kind:             "response",
		Type:             "download_status",
		Status:           "progress",
		DownloadID:       downloadID,
		BytesTransferred: current,
		TotalBytes:       total,
	}:
	default:
		// 通道满则丢弃，避免阻塞终端
	}
}

func writeCLIResponse(conn net.Conn, resp protocol.CLIResponse) {
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

func (terminal *TerminalSession) clientSnapshotLocked() []*Client {
	clients := make([]*Client, 0, len(terminal.clients))
	for client := range terminal.clients {
		clients = append(clients, client)
	}
	return clients
}

func (terminal *TerminalSession) touchLocked() {
	terminal.activeAt = time.Now().UTC()
}

func displayTitle(name string, termTitle string) string {
	cleanName := normalize(name)
	cleanTermTitle := normalize(termTitle)
	if cleanTermTitle == "" {
		cleanTermTitle = "Terminal"
	}
	if cleanName != "" {
		return cleanName + " - " + cleanTermTitle
	}
	return cleanTermTitle
}

func normalize(value string) string {
	return strings.TrimSpace(value)
}

func reverse(frames []EventFrame) {
	for i, j := 0, len(frames)-1; i < j; i, j = i+1, j-1 {
		frames[i], frames[j] = frames[j], frames[i]
	}
}

func randomID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return time.Now().UTC().Format("20060102150405.000000000")
	}
	return hex.EncodeToString(bytes[:])
}
