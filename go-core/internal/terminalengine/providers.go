package terminalengine

import (
	"image/color"
	"io"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// Option 配置 Engine。
type Option func(*Engine)

// WithPTYWriter 设置终端响应写入器。
func WithPTYWriter(w io.Writer) Option {
	return func(e *Engine) {
		e.ptyWriter = w
	}
}

// WithBellHandler 设置 bell 回调。
func WithBellHandler(fn func()) Option {
	return func(e *Engine) {
		e.onBell = fn
	}
}

// WithTitleHandler 设置 title 变化回调。
func WithTitleHandler(fn func(string)) Option {
	return func(e *Engine) {
		e.onTitle = fn
	}
}

// WithWorkingDirectoryHandler 设置工作目录变化回调。
func WithWorkingDirectoryHandler(fn func(string)) Option {
	return func(e *Engine) {
		e.onWorkingDirectory = fn
	}
}

// WithClipboardReadHandler 设置剪贴板读取回调。
func WithClipboardReadHandler(fn func(clipboard byte)) Option {
	return func(e *Engine) {
		e.onClipboardRead = fn
	}
}

// WithClipboardWriteHandler 设置剪贴板写入回调。
func WithClipboardWriteHandler(fn func(clipboard byte, data []byte)) Option {
	return func(e *Engine) {
		e.onClipboardWrite = fn
	}
}

// WithNotificationHandler 设置桌面通知回调。
func WithNotificationHandler(fn func(*headlessterm.NotificationPayload)) Option {
	return func(e *Engine) {
		e.onNotification = fn
	}
}

// engineBellProvider 实现 headlessterm.BellProvider。
type engineBellProvider struct {
	onBell func()
}

func (p *engineBellProvider) Ring() {
	if p.onBell != nil {
		p.onBell()
	}
}

// engineClipboardProvider 实现 headlessterm.ClipboardProvider。
type engineClipboardProvider struct {
	onRead  func(clipboard byte)
	onWrite func(clipboard byte, data []byte)
}

func (p *engineClipboardProvider) Read(clipboard byte) string {
	if p.onRead != nil {
		p.onRead(clipboard)
	}
	return ""
}

func (p *engineClipboardProvider) Write(clipboard byte, data []byte) {
	if p.onWrite != nil {
		p.onWrite(clipboard, data)
	}
}

// engineNotificationProvider 实现 headlessterm.NotificationProvider。
type engineNotificationProvider struct {
	onNotify func(*headlessterm.NotificationPayload)
}

func (p *engineNotificationProvider) Notify(payload *headlessterm.NotificationPayload) string {
	if p.onNotify != nil {
		p.onNotify(payload)
	}
	return ""
}

// CellColor 转换 headless-term 颜色到传输无关 Color。
func CellColor(c color.Color) Color {
	if c == nil {
		return Color{Kind: ColorDefaultFG}
	}
	switch v := c.(type) {
	case *headlessterm.NamedColor:
		switch v.Name {
		case headlessterm.NamedColorForeground:
			return Color{Kind: ColorDefaultFG}
		case headlessterm.NamedColorBackground:
			return Color{Kind: ColorDefaultBG}
		case headlessterm.NamedColorCursor:
			return Color{Kind: ColorCursor}
		}
		return Color{Kind: ColorIndexed, Index: int(v.Name)}
	case *headlessterm.IndexedColor:
		return Color{Kind: ColorIndexed, Index: v.Index}
	default:
		r, g, b, _ := c.RGBA()
		return Color{Kind: ColorRGB, RGB: (uint32(r>>8) << 16) | (uint32(g>>8) << 8) | uint32(b>>8)}
	}
}
