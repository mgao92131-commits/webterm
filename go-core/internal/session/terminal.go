package session

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"strings"
	"sync"
	"time"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/infrastructure/emulator"
	"webterm/go-core/internal/infrastructure/pty"
	"webterm/go-core/internal/protocol"
)

const (
	DefaultCols = 100
	DefaultRows = 30

	// StateBytes 重建 ANSI 文本时最多包含的 scrollback 行数，避免重连时全量复制 10k 行。
	maxStateScrollbackLines = 1000
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
	mu               sync.RWMutex
	id               string
	instance         string
	name             string
	termTitle        string
	cwd              string
	liveCwd          string
	command          string
	status           string
	shellState       string
	agentState       string
	gitBranch        string
	lastInput        *LastInput
	notification     *Notification
	cols             int
	rows             int
	createdAt        time.Time
	activeAt         time.Time
	ring             *EventRing
	screen           *ScreenState
	process          *pty.Process
	clients          map[*Client]struct{}
	onTitleChanged   func()
	onInfoChanged    func()
	titleChanged     bool
	stateCache       []byte
	stateCacheValid  bool
	stateCacheGen    uint64
}

type Notification struct {
	Title     string `json:"title"`
	Body      string `json:"body,omitempty"`
	Level     string `json:"level,omitempty"`
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

	var terminal *TerminalSession
	// Note: onTitle is triggered synchronously inside screen.Write, which is called
	// within pushOutputLocked while terminal.mu is held. Thus directly updating the
	// terminal fields here is thread-safe and free from data races.
	// WARNING: Do NOT attempt to acquire terminal.mu.Lock() inside this callback,
	// as that will cause a deadlock since the lock is already held by the caller.
	titleProvider := &emulator.TerminalTitleProvider{
		OnTitle: func(title string) {
			if terminal != nil {
				terminal.termTitle = title
				terminal.titleChanged = true
			}
		},
	}

	terminal = &TerminalSession{
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
		screen:         NewScreenState(rows, cols, process.PTY(), titleProvider),
		process:        process,
		clients:        make(map[*Client]struct{}),
		onTitleChanged: options.OnTitle,
		onInfoChanged:  options.OnInfoChanged,
	}
	go terminal.readLoop()
	go terminal.waitLoop()
	return terminal, nil
}

func (terminal *TerminalSession) ID() string {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	return terminal.id
}

func (terminal *TerminalSession) Info() Info {
	terminal.mu.RLock()
	defer terminal.mu.RUnlock()
	cwd := terminal.cwd
	if terminal.liveCwd != "" {
		cwd = terminal.liveCwd
	}
	return Info{
		ID:                terminal.id,
		InstanceID:        terminal.instance,
		Name:              terminal.name,
		TermTitle:         terminal.termTitle,
		DisplayTitle:      displayTitle(terminal.name, terminal.termTitle),
		CWD:               cwd,
		RecentInputLines:  []string{},
		RecentInputHidden: false,
		Command:           terminal.command,
		Status:            terminal.status,
		ShellState:        terminal.shellState,
		AgentState:        terminal.agentState,
		GitBranch:         terminal.gitBranch,
		LastCommand:       terminal.lastInputText(),
		LastInputKind:     terminal.lastInputKind(),
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
	if terminal.screen != nil {
		terminal.screen.Resize(rows, cols)
	}
	if terminal.process != nil {
		terminal.process.Resize(cols, rows)
	}
	terminal.touchLocked()
	terminal.invalidateStateCache()
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

// terminalModes 生成终端模式恢复 ANSI 序列（与 @xterm/addon-serialize 对齐）
func (terminal *TerminalSession) terminalModes() []byte {
	terminal.mu.RLock()
	screen := terminal.screen
	terminal.mu.RUnlock()
	if screen == nil {
		return nil
	}

	var buf []byte
	t := screen.Terminal

	// 默认 false 的模式，启用时输出 set 序列
	if t.HasMode(headlessterm.ModeCursorKeys) {
		buf = append(buf, "\x1b[?1h"...)
	}
	if t.HasMode(headlessterm.ModeKeypadApplication) {
		buf = append(buf, "\x1b[?66h"...)
	}
	if t.HasMode(headlessterm.ModeBracketedPaste) {
		buf = append(buf, "\x1b[?2004h"...)
	}
	if t.HasMode(headlessterm.ModeInsert) {
		buf = append(buf, "\x1b[4h"...)
	}
	if t.HasMode(headlessterm.ModeOrigin) {
		buf = append(buf, "\x1b[?6h"...)
	}
	if t.HasMode(headlessterm.ModeReportFocusInOut) {
		buf = append(buf, "\x1b[?1004h"...)
	}

	// 鼠标上报模式
	if t.HasMode(headlessterm.ModeReportAllMouseMotion) {
		buf = append(buf, "\x1b[?1003h"...)
	} else if t.HasMode(headlessterm.ModeReportCellMouseMotion) {
		buf = append(buf, "\x1b[?1002h"...)
	} else if t.HasMode(headlessterm.ModeReportMouseClicks) {
		buf = append(buf, "\x1b[?1000h"...)
	}

	// 默认 true 的模式，禁用时输出 reset 序列
	if !t.HasMode(headlessterm.ModeLineWrap) {
		buf = append(buf, "\x1b[?7l"...)
	}

	return buf
}

func (terminal *TerminalSession) StateBytes() []byte {
	terminal.mu.RLock()
	cache := terminal.stateCache
	valid := terminal.stateCacheValid
	gen := terminal.stateCacheGen
	screen := terminal.screen
	terminal.mu.RUnlock()
	if valid {
		return append([]byte(nil), cache...)
	}

	var out []byte
	if screen != nil {
		text := screen.AnsiTextWithScrollbackLimit(maxStateScrollbackLines)
		out = []byte("\x1b[3J\x1b[2J\x1b[H")
		if text != "" {
			out = append(out, []byte(text)...)
		}
		// 追加终端模式恢复序列（与 @xterm/addon-serialize 对齐）
		if modes := terminal.terminalModes(); len(modes) > 0 {
			out = append(out, modes...)
		}
	} else {
		terminal.mu.RLock()
		frames := terminal.ring.After(0)
		terminal.mu.RUnlock()
		const maxBytes = 256 * 1024
		total := 0
		var selected []EventFrame
		for i := len(frames) - 1; i >= 0; i-- {
			if total+len(frames[i].Bytes) > maxBytes && len(selected) > 0 {
				break
			}
			selected = append(selected, frames[i])
			total += len(frames[i].Bytes)
		}
		reverse(selected)
		out = []byte("\x1b[3J\x1b[2J\x1b[H")
		for _, frame := range selected {
			out = append(out, frame.Bytes...)
		}
	}

	terminal.mu.Lock()
	if terminal.stateCacheGen == gen {
		terminal.stateCache = append([]byte(nil), out...)
		terminal.stateCacheValid = true
	}
	terminal.mu.Unlock()
	return out
}

func (terminal *TerminalSession) ScreenSnapshot() *ScreenSnapshot {
	terminal.mu.RLock()
	screen := terminal.screen
	terminal.mu.RUnlock()
	if screen == nil {
		return nil
	}
	return screen.Snapshot("styled")
}

func (terminal *TerminalSession) ScreenDelta() ScreenDelta {
	terminal.mu.RLock()
	screen := terminal.screen
	seq := terminal.ring.LatestSeq()
	terminal.mu.RUnlock()
	if screen == nil {
		return ScreenDelta{Seq: seq}
	}
	return screen.DirtyDelta(seq)
}

func (terminal *TerminalSession) readLoop() {
	buf := make([]byte, 32*1024)
	for {
		n, err := terminal.process.Read(buf)
		if n > 0 {
			bytes := append([]byte(nil), buf[:n]...)
			frame := terminal.PushOutput(bytes)
			terminal.broadcastOutput(frame)
		}
		if err != nil {
			if err != io.EOF {
				terminal.markClosed()
			}
			return
		}
	}
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
	if terminal.screen != nil {
		if cwd, err := terminal.screen.WriteAndWorkingDirectoryPath(data); err == nil && cwd != "" && cwd != terminal.liveCwd {
			terminal.liveCwd = cwd
			terminal.titleChanged = true
		}
	}
	terminal.touchLocked()
	terminal.invalidateStateCache()
	return terminal.ring.Push(data)
}

// invalidateStateCache 必须在 terminal.mu 锁内调用。
func (terminal *TerminalSession) invalidateStateCache() {
	terminal.stateCacheValid = false
	terminal.stateCache = nil
	terminal.stateCacheGen++
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
	delta := terminal.ScreenDelta()
	for _, client := range clients {
		client.SendOutput(frame, delta)
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
			Title:     ev.Title,
			Body:      ev.Body,
			Level:     ev.Level,
			Timestamp: ev.Timestamp,
		}
		terminal.touchLocked()
	case "state":
		if ev.ShellState != "" {
			terminal.shellState = ev.ShellState
		}
		if ev.AgentState != "" {
			terminal.agentState = ev.AgentState
			if ev.AgentState == "running" {
				terminal.notification = nil
			}
		}
		terminal.touchLocked()
	case "meta":
		if ev.CWD != "" {
			terminal.liveCwd = ev.CWD
		}
		if ev.GitBranch != "" {
			terminal.gitBranch = ev.GitBranch
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
