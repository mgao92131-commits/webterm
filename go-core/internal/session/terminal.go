package session

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"sync"
	"time"

	"webterm/go-core/internal/infrastructure/pty"
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

	// 进程退出后先排空 PTY 尾部输出与最终投影，再广播 Exit，
	// 保证客户端在收到 Exit 之前看到最终画面。超时兜底防止异常占用
	// PTY 的进程树让会话永远无法关闭。
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	// BeginDrain 停止输出生产端：Windows 上关闭伪控制台让输出管道产生真正的 EOF
	// （Unix 为 no-op，PTY 本就随子进程退出 EOF）。必须在 DrainAndClose 之前调用，
	// 这样 Runtime 的 readLoop 能读到真 EOF，而不是靠静默窗口猜测尾部已排空。
	// BeginDrain 失败时告知 Runtime 退化为静默兜底。
	if terminal.process != nil {
		if err := terminal.process.BeginDrain(); err != nil && terminal.runtime != nil {
			terminal.runtime.MarkDrainEOFUncertain()
		}
	}
	if terminal.runtime != nil {
		_ = terminal.runtime.DrainAndClose(ctx)
	}
	terminal.flushClientScreens(ctx)
	terminal.broadcastExit(code)
	// Wait only observes child termination. Close owns the backend handles and
	// must still run on natural shell exit to release ConPTY/Job/pipes.
	if terminal.process != nil {
		_ = terminal.process.Close()
	}
	terminal.markClosed()
}

// flushClientScreens 等待各客户端尚未写出的最终屏幕帧完成 socket 写入，
// 使 Exit 不会超过最后一帧先到达客户端。
func (terminal *TerminalSession) flushClientScreens(ctx context.Context) {
	terminal.mu.RLock()
	clients := terminal.clientSnapshotLocked()
	terminal.mu.RUnlock()
	for _, client := range clients {
		client.flushScreenPending(ctx)
	}
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
