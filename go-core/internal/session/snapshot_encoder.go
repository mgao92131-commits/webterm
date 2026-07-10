package session

import "webterm/go-core/internal/infrastructure/emulator"

// SnapshotMode 表示快照目标线协议。
type SnapshotMode int

const (
	// SnapshotModeJSON 生成 JSON state 的 payload，不含 binary 专用清屏前缀。
	SnapshotModeJSON SnapshotMode = iota
	// SnapshotModeBinary 生成 binary MSG_STATE 的 payload，含清屏前缀。
	SnapshotModeBinary
)

// clearStatePrefix 是 binary state 使用的清屏序列。
const clearStatePrefix = "\x1b[3J\x1b[2J\x1b[H"

// SnapshotEncoder 负责把终端屏幕状态编码为 Node 兼容的 state payload。
// 它不处理锁、缓存、ring 或 client 生命周期；这些由 TerminalSession 负责。
type SnapshotEncoder struct {
	screen          *emulator.Screen
	terminalModes   func() []byte
	scrollbackLimit int
}

// NewSnapshotEncoder 创建新的快照编码器。
func NewSnapshotEncoder(screen *emulator.Screen, terminalModes func() []byte) *SnapshotEncoder {
	return &SnapshotEncoder{
		screen:          screen,
		terminalModes:   terminalModes,
		scrollbackLimit: maxStateScrollbackLines,
	}
}

// SetScrollbackLimit 设置 scrollback 上限；<=0 表示不限制。
func (e *SnapshotEncoder) SetScrollbackLimit(limit int) {
	e.scrollbackLimit = limit
}

// Encode 返回指定线协议的 state payload。
func (e *SnapshotEncoder) Encode(mode SnapshotMode) []byte {
	payload := e.encodePayload()
	if mode == SnapshotModeBinary {
		out := make([]byte, 0, len(clearStatePrefix)+len(payload))
		out = append(out, clearStatePrefix...)
		out = append(out, payload...)
		return out
	}
	return payload
}

func (e *SnapshotEncoder) encodePayload() []byte {
	if e.screen == nil {
		return nil
	}
	var payload []byte
	text := e.screen.AnsiTextWithScrollbackLimit(e.scrollbackLimit)
	if text != "" {
		payload = append(payload, []byte(text)...)
	}
	if modes := e.terminalModes(); len(modes) > 0 {
		payload = append(payload, modes...)
	}
	return payload
}
