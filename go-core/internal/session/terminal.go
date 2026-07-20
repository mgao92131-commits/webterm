package session

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
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
	ID      string
	CWD     string
	Command string
	Args    []string
	Cols    int
	Rows    int
	Env     map[string]string
	// ScrollbackMaxLines/ScrollbackMaxBytes 是 scrollback 双上限；非正值使用默认。
	ScrollbackMaxLines int
	ScrollbackMaxBytes int
	OnTitle            func()
	OnInfoChanged      func()
}

type TerminalSession struct {
	mu              sync.RWMutex
	id              string
	instance        string
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
	runtime         *terminalsession.Runtime
	process         *pty.Process
	processIdentity pty.Identity
	clients         map[*terminalChannelRuntime]struct{}
	screenOwners    map[string]*terminalChannelRuntime
	onTitleChanged  func()
	onInfoChanged   func()
	titleChanged    bool
	manager         *Manager
	// 已断开客户端的 screen 发送累计；避免重连后诊断统计丢失历史流量。
	screenWireHistory ScreenWireSnapshot
}

type Notification struct {
	Level      string `json:"level,omitempty"`
	Importance string `json:"importance,omitempty"`
	Message    string `json:"message"`
	Source     string `json:"source,omitempty"`
	Timestamp  int64  `json:"timestamp"`
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
		id:              options.ID,
		instance:        randomID(),
		cwd:             process.CWD(),
		command:         process.Command(),
		status:          StatusRunning,
		cols:            cols,
		rows:            rows,
		createdAt:       now,
		activeAt:        now,
		process:         process,
		processIdentity: process.Identity(),
		clients:         make(map[*terminalChannelRuntime]struct{}),
		screenOwners:    make(map[string]*terminalChannelRuntime),
		onTitleChanged:  options.OnTitle,
		onInfoChanged:   options.OnInfoChanged,
	}
	terminal.runtime = terminalsession.NewRuntime(
		terminal.id,
		process,
		rows,
		cols,
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
		terminalsession.WithScrollbackLimits(options.ScrollbackMaxLines, options.ScrollbackMaxBytes),
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
	return terminal.processIdentity.PID
}

func (terminal *TerminalSession) ProcessIdentity() pty.Identity {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.processIdentity
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
		TermTitle:         terminal.termTitle,
		CWD:               cwd,
		RecentInputLines:  []string{},
		RecentInputHidden: false,
		Command:           terminal.command,
		Status:            terminal.status,
		ShellState:        terminal.shellState,
		LastCommand:       terminal.lastInputText(),
		LastInputKind:     terminal.lastInputKind(),
		TTY:               terminal.processIdentity.TerminalKey,
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

// SnapshotUploadCWD 在上传开始时冻结当前工作目录。
func (terminal *TerminalSession) SnapshotUploadCWD() (string, error) {
	terminal.mu.RLock()
	if terminal.status == StatusClosed {
		terminal.mu.RUnlock()
		return "", errors.New("terminal session is closed")
	}
	cwd := terminal.liveCwd
	if cwd == "" {
		cwd = terminal.cwd
	}
	terminal.mu.RUnlock()
	if cwd == "" {
		return "", errors.New("terminal working directory is unavailable")
	}
	info, err := os.Stat(cwd)
	if err != nil || !info.IsDir() {
		return "", errors.New("terminal working directory is unavailable")
	}
	return cwd, nil
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

	if terminal.runtime != nil {
		_ = terminal.runtime.Close()
	}
	if terminal.process != nil {
		_ = terminal.process.Close()
	}
	for _, client := range clients {
		client.Close()
	}
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
	terminal.mu.RLock()
	if terminal.status == StatusClosed || terminal.process == nil {
		terminal.mu.RUnlock()
		return errors.New("terminal session is closed")
	}
	process := terminal.process
	runtime := terminal.runtime
	terminal.mu.RUnlock()

	if err := process.Resize(cols, rows); err != nil {
		return err
	}

	terminal.mu.Lock()
	if terminal.status == StatusClosed || terminal.process != process {
		terminal.mu.Unlock()
		return errors.New("terminal session is closed")
	}
	terminal.cols = cols
	terminal.rows = rows
	terminal.touchLocked()
	terminal.mu.Unlock()
	if runtime != nil {
		runtime.ResizeEngine(rows, cols)
	}
	return nil
}

func (terminal *TerminalSession) Attach(client *terminalChannelRuntime) {
	terminal.mu.Lock()
	var replaced *terminalChannelRuntime
	if client.ownerKey != "" {
		replaced = terminal.screenOwners[client.ownerKey]
		terminal.screenOwners[client.ownerKey] = client
	}
	terminal.clients[client] = struct{}{}
	terminal.touchLocked()
	terminal.mu.Unlock()
	if replaced != nil && replaced != client {
		replaced.Close()
	}
}

func (terminal *TerminalSession) Detach(client *terminalChannelRuntime) {
	terminal.mu.Lock()
	delete(terminal.clients, client)
	if client.ownerKey != "" && terminal.screenOwners[client.ownerKey] == client {
		delete(terminal.screenOwners, client.ownerKey)
	}
	// 客户端断开前把其发送累计保留到会话历史，防止重连后统计消失。
	terminal.screenWireHistory = mergeScreenWireSnapshot(terminal.screenWireHistory, client.ScreenWireSnapshot())
	terminal.touchLocked()
	terminal.mu.Unlock()
}

func mergeScreenWireSnapshot(a, b ScreenWireSnapshot) ScreenWireSnapshot {
	return ScreenWireSnapshot{
		FrameCount:       a.FrameCount + b.FrameCount,
		WireBytes:        a.WireBytes + b.WireBytes,
		SnapshotBytes:    a.SnapshotBytes + b.SnapshotBytes,
		PatchBytes:       a.PatchBytes + b.PatchBytes,
		HistoryPageBytes: a.HistoryPageBytes + b.HistoryPageBytes,
		OtherBytes:       a.OtherBytes + b.OtherBytes,
	}
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

// PTYOutputSnapshot 返回该会话 PTY 输出的累计事件数和字节数。
func (terminal *TerminalSession) PTYOutputSnapshot() (events, bytes uint64) {
	terminal.mu.RLock()
	rt := terminal.runtime
	terminal.mu.RUnlock()
	if rt == nil {
		return 0, 0
	}
	return rt.PTYOutputSnapshot()
}

// ScreenWireSnapshots 返回会话所有 screen channel 的发送字节累计，包含当前已连接客户端
// 与已断开客户端的历史累计。
func (terminal *TerminalSession) ScreenWireSnapshots() map[string]ScreenWireSnapshot {
	terminal.mu.RLock()
	clients := make([]*terminalChannelRuntime, 0, len(terminal.clients))
	for client := range terminal.clients {
		clients = append(clients, client)
	}
	history := terminal.screenWireHistory
	terminal.mu.RUnlock()

	result := make(map[string]ScreenWireSnapshot, len(clients)+1)
	for _, client := range clients {
		result[client.screenClientID] = client.ScreenWireSnapshot()
	}
	if history.FrameCount > 0 || history.WireBytes > 0 {
		result["_disconnected"] = history
	}
	return result
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

func (terminal *TerminalSession) waitLoop() {
	code, _ := terminal.process.Wait()
	if terminal.runtime != nil {
		_ = terminal.runtime.Close()
	}
	terminal.markClosed()
	terminal.broadcastExit(code)
}

func (terminal *TerminalSession) markClosed() {
	terminal.mu.Lock()
	terminal.status = StatusClosed
	terminal.touchLocked()
	terminal.mu.Unlock()
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

// ApplyNotification updates the session card state. Device notifications are
// dispatched separately by localipc so terminal screen traffic never carries
// notification payloads.
func (terminal *TerminalSession) ApplyNotification(importance, message, source string, timestamp int64) {
	if timestamp == 0 {
		timestamp = time.Now().Unix()
	}
	terminal.mu.Lock()
	if terminal.status == StatusClosed {
		terminal.mu.Unlock()
		return
	}
	terminal.notification = &Notification{
		Importance: importance,
		Message:    message,
		Source:     source,
		Timestamp:  timestamp,
	}
	terminal.touchLocked()
	onInfoChanged := terminal.onInfoChanged
	terminal.mu.Unlock()
	if onInfoChanged != nil {
		onInfoChanged()
	}
	terminal.broadcastInfo()
}

// ApplySessionUpdate changes only terminal metadata and never triggers a
// device notification.
func (terminal *TerminalSession) ApplySessionUpdate(shellState, cwd, lastInput, inputKind string, timestamp int64) {
	if timestamp == 0 {
		timestamp = time.Now().Unix()
	}
	terminal.mu.Lock()
	if terminal.status == StatusClosed {
		terminal.mu.Unlock()
		return
	}
	var runtimeCWD string
	if shellState != "" {
		terminal.shellState = shellState
	}
	if cwd != "" {
		terminal.liveCwd = cwd
		runtimeCWD = cwd
	}
	if lastInput != "" {
		if inputKind == "" {
			inputKind = "shell"
		}
		terminal.lastInput = &LastInput{Kind: inputKind, Text: lastInput, Timestamp: timestamp}
	}
	terminal.touchLocked()
	onInfoChanged := terminal.onInfoChanged
	runtime := terminal.runtime
	terminal.mu.Unlock()
	if runtimeCWD != "" && runtime != nil {
		runtime.SetWorkingDirectory(runtimeCWD)
	}
	if onInfoChanged != nil {
		onInfoChanged()
	}
	terminal.broadcastInfo()
}

func (terminal *TerminalSession) broadcastInfo() {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.SendInfo()
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

func (terminal *TerminalSession) clientSnapshotLocked() []*terminalChannelRuntime {
	clients := make([]*terminalChannelRuntime, 0, len(terminal.clients))
	for client := range terminal.clients {
		clients = append(clients, client)
	}
	return clients
}

func (terminal *TerminalSession) touchLocked() {
	terminal.activeAt = time.Now().UTC()
}

func randomID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return time.Now().UTC().Format("20060102150405.000000000")
	}
	return hex.EncodeToString(bytes[:])
}
