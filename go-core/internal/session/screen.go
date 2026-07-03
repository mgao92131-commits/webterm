package session

import (
	"io"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/infrastructure/emulator"
)

// ScreenState 是 emulator.Screen 的类型别名，保持向后兼容。
type ScreenState = emulator.Screen

// NewScreenState 创建新的屏幕状态。
func NewScreenState(rows int, cols int, responses io.Writer, title headlessterm.TitleProvider) *ScreenState {
	return emulator.NewScreen(rows, cols, responses, title)
}

// ScreenDirtyCell 是 emulator.DirtyCell 的别名。
type ScreenDirtyCell = emulator.DirtyCell

// ScreenDelta 是 emulator.Delta 的别名。
type ScreenDelta = emulator.Delta

// ScreenSnapshot 是 emulator.Snapshot 的别名。
type ScreenSnapshot = emulator.Snapshot
