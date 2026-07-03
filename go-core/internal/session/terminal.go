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
)

const (
	DefaultCols = 100
	DefaultRows = 30
)

type TerminalOptions struct {
	ID      string
	Name    string
	CWD     string
	Command string
	Cols    int
	Rows    int
	OnTitle func()
}

type TerminalSession struct {
	mu             sync.RWMutex
	id             string
	instance       string
	name           string
	termTitle      string
	cwd            string
	liveCwd        string
	command        string
	status         string
	cols           int
	rows           int
	createdAt      time.Time
	activeAt       time.Time
	ring           *EventRing
	screen         *ScreenState
	process        *pty.Process
	clients        map[*Client]struct{}
	onTitleChanged func()
	titleChanged   bool
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
		Cols:    cols,
		Rows:    rows,
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
	}
	go terminal.readLoop()
	go terminal.waitLoop()
	return terminal, nil
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
		Clients:           len(terminal.clients),
		Cols:              terminal.cols,
		Rows:              terminal.rows,
		CreatedAt:         terminal.createdAt,
		LastActiveAt:      terminal.activeAt,
	}
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
	terminal.mu.Unlock()

	if changed && onTitleChanged != nil {
		onTitleChanged()
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
	_, err := terminal.process.Write(data)
	return err
}

func (terminal *TerminalSession) Resize(cols int, rows int) error {
	if err := terminal.process.Resize(cols, rows); err != nil {
		return err
	}
	terminal.mu.Lock()
	terminal.cols = terminal.process.Cols()
	terminal.rows = terminal.process.Rows()
	if terminal.screen != nil {
		terminal.screen.Resize(terminal.rows, terminal.cols)
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

// terminalModes 生成终端模式恢复 ANSI 序列（与 @xterm/addon-serialize 对齐）
func (terminal *TerminalSession) terminalModes() []byte {
	terminal.mu.RLock()
	screen := terminal.screen
	terminal.mu.RUnlock()
	if screen == nil {
		return nil
	}

	var buf []byte
	t := screen.terminal

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
	screen := terminal.screen
	if screen != nil {
		terminal.mu.RUnlock()
		text := screen.AnsiText()
		out := []byte("\x1b[3J\x1b[2J\x1b[H")
		if text != "" {
			out = append(out, []byte(text)...)
		}
		// 追加终端模式恢复序列（与 @xterm/addon-serialize 对齐）
		if modes := terminal.terminalModes(); len(modes) > 0 {
			out = append(out, modes...)
		}
		return out
	}
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
	out := []byte("\x1b[3J\x1b[2J\x1b[H")
	for _, frame := range selected {
		out = append(out, frame.Bytes...)
	}
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
