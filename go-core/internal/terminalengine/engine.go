package terminalengine

import (
	"io"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// Engine 包装 headless terminal，是唯一终端状态源。
type Engine struct {
	terminal           *headlessterm.Terminal
	scrollback         *TrackedScrollback
	onTitle            func(string)
	onBell             func()
	onWorkingDirectory func(string)
	onClipboardRead    func(clipboard byte)
	onClipboardWrite   func(clipboard byte, data []byte)
	onNotification     func(*headlessterm.NotificationPayload)
	ptyWriter          io.Writer
}

type engineTitleProvider struct {
	onTitle func(string)
	title   string
	stack   []string
}

func (p *engineTitleProvider) SetTitle(title string) {
	p.title = title
	if p.onTitle != nil {
		p.onTitle(title)
	}
}

func (p *engineTitleProvider) PushTitle() {
	p.stack = append(p.stack, p.title)
}

func (p *engineTitleProvider) PopTitle() {
	if len(p.stack) == 0 {
		return
	}
	last := len(p.stack) - 1
	p.title = p.stack[last]
	p.stack = p.stack[:last]
	if p.onTitle != nil {
		p.onTitle(p.title)
	}
}

// NewEngine 创建新的终端 engine。
func NewEngine(rows, cols int, scrollback *TrackedScrollback, options ...Option) *Engine {
	if rows <= 0 {
		rows = 30
	}
	if cols <= 0 {
		cols = 100
	}
	e := &Engine{
		scrollback: scrollback,
	}
	for _, opt := range options {
		opt(e)
	}

	// 使用本地 provider 以便捕获副作用。
	titleProvider := &engineTitleProvider{
		onTitle: func(title string) {
			if e.onTitle != nil {
				e.onTitle(title)
			}
		},
	}
	opts := []headlessterm.Option{
		headlessterm.WithSize(rows, cols),
		headlessterm.WithScrollback(scrollback),
		headlessterm.WithTitle(titleProvider),
	}
	if e.ptyWriter != nil {
		opts = append(opts, headlessterm.WithPTYWriter(e.ptyWriter))
	}
	if e.onBell != nil {
		opts = append(opts, headlessterm.WithBell(&engineBellProvider{onBell: e.onBell}))
	}
	if e.onClipboardRead != nil || e.onClipboardWrite != nil {
		opts = append(opts, headlessterm.WithClipboard(&engineClipboardProvider{
			onRead:  e.onClipboardRead,
			onWrite: e.onClipboardWrite,
		}))
	}
	if e.onWorkingDirectory != nil {
		opts = append(opts, headlessterm.WithMiddleware(&headlessterm.Middleware{
			SetWorkingDirectory: func(uri string, next func(string)) {
				next(uri)
				e.onWorkingDirectory(e.WorkingDirectory())
			},
		}))
	}
	if e.onNotification != nil {
		opts = append(opts, headlessterm.WithNotification(&engineNotificationProvider{onNotify: e.onNotification}))
	}

	e.terminal = headlessterm.New(opts...)
	return e
}

// Write 写入 PTY 输出。
func (e *Engine) Write(data []byte) error {
	_, err := e.terminal.Write(data)
	return err
}

// Resize 调整大小。
func (e *Engine) Resize(rows, cols int) {
	e.terminal.Resize(rows, cols)
}

// Rows 返回当前行数。
func (e *Engine) Rows() int {
	return e.terminal.Rows()
}

// Cols 返回当前列数。
func (e *Engine) Cols() int {
	return e.terminal.Cols()
}

// IsAlternateScreen 返回是否在备用屏。
func (e *Engine) IsAlternateScreen() bool {
	return e.terminal.IsAlternateScreen()
}

// CursorPos 返回光标位置。
func (e *Engine) CursorPos() (row, col int) {
	return e.terminal.CursorPos()
}

// CursorVisible 返回光标是否可见。
func (e *Engine) CursorVisible() bool {
	return e.terminal.CursorVisible()
}

// WorkingDirectory 返回当前工作目录（通过 OSC 7 / shell integration）。
func (e *Engine) WorkingDirectory() string {
	return e.terminal.WorkingDirectoryPath()
}

// Cell 返回指定位置的 cell。
func (e *Engine) Cell(row, col int) *headlessterm.Cell {
	return e.terminal.Cell(row, col)
}

// IsWrapped 返回行是否软换行。
func (e *Engine) IsWrapped(row int) bool {
	return e.terminal.IsWrapped(row)
}

// ReadProjection 返回终端的原子只读投影：一次读锁内的元数据快照加上自上次
// ConsumeProjectionDirty 以来变化的行（Full 时为全部行）。语义见
// headlessterm.Terminal.ReadProjection。
func (e *Engine) ReadProjection() headlessterm.ProjectionRead {
	return e.terminal.ReadProjection()
}

// ReadFullProjection 返回包含全部行的完整投影，无视 dirty 状态；用于
// Projector 缓存被丢弃后（epoch/字典世代重建）的全量重建。
func (e *Engine) ReadFullProjection() headlessterm.ProjectionRead {
	return e.terminal.ReadFullProjection()
}

// ConsumeProjectionDirty 在投影行合并进 Projector 缓存后清除 dirty 状态并
// 推进光标基线。调用方必须保证 ReadProjection 与本调用之间没有写入到达终端
// （runtime actor 单 goroutine 串行化天然满足）。
func (e *Engine) ConsumeProjectionDirty(p headlessterm.ProjectionRead) {
	e.terminal.ConsumeProjectionDirty(p)
}

// CursorStyle 返回光标样式。
func (e *Engine) CursorStyle() headlessterm.CursorStyle {
	return e.terminal.CursorStyle()
}

// HasMode 返回是否设置指定模式。
func (e *Engine) HasMode(mode headlessterm.TerminalMode) bool {
	return e.terminal.HasMode(mode)
}

// ScrollbackLen 返回历史行数。
func (e *Engine) ScrollbackLen() int {
	return e.terminal.ScrollbackLen()
}

// ScrollbackLine 返回历史行。
func (e *Engine) ScrollbackLine(index int) headlessterm.ScrollbackLine {
	return e.terminal.ScrollbackLine(index)
}

// Scrollback 返回底层 tracked scrollback。
func (e *Engine) Scrollback() *TrackedScrollback {
	return e.scrollback
}

// Title 返回当前标题。
func (e *Engine) Title() string {
	return e.terminal.Title()
}
