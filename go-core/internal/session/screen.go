package session

import (
	"io"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

const screenScrollbackLines = 10000

type ScreenDirtyCell struct {
	Row  int    `json:"row"`
	Col  int    `json:"col"`
	Char string `json:"char"`
}

type ScreenDelta struct {
	Seq   uint64            `json:"seq"`
	Cells []ScreenDirtyCell `json:"cells"`
}

type ScreenSnapshot = headlessterm.Snapshot

type ScreenState struct {
	mu       sync.Mutex
	terminal *headlessterm.Terminal
}

func NewScreenState(rows int, cols int, responses io.Writer, title headlessterm.TitleProvider) *ScreenState {
	if rows <= 0 {
		rows = DefaultRows
	}
	if cols <= 0 {
		cols = DefaultCols
	}

	options := []headlessterm.Option{
		headlessterm.WithSize(rows, cols),
		headlessterm.WithScrollback(headlessterm.NewMemoryScrollback(screenScrollbackLines)),
	}
	if responses != nil {
		options = append(options, headlessterm.WithPTYWriter(responses))
	}
	if title != nil {
		options = append(options, headlessterm.WithTitle(title))
	}

	return &ScreenState{terminal: headlessterm.New(options...)}
}

func (screen *ScreenState) Write(data []byte) error {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	_, err := screen.terminal.Write(data)
	return err
}

func (screen *ScreenState) Resize(rows int, cols int) {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	screen.terminal.Resize(rows, cols)
}

func (screen *ScreenState) Snapshot(detail headlessterm.SnapshotDetail) *headlessterm.Snapshot {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.Snapshot(detail)
}

func (screen *ScreenState) Text() string {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.String()
}

func (screen *ScreenState) DirtyDelta(seq uint64) ScreenDelta {
	screen.mu.Lock()
	defer screen.mu.Unlock()

	positions := screen.terminal.DirtyCells()
	cells := make([]ScreenDirtyCell, 0, len(positions))
	for _, position := range positions {
		cell := screen.terminal.Cell(position.Row, position.Col)
		if cell == nil {
			continue
		}
		cells = append(cells, ScreenDirtyCell{
			Row:  position.Row,
			Col:  position.Col,
			Char: string(cell.Char),
		})
	}
	screen.terminal.ClearDirty()
	return ScreenDelta{Seq: seq, Cells: cells}
}

type terminalTitleProvider struct {
	onTitle func(string)
	stack   []string
	current string
}

func (provider *terminalTitleProvider) SetTitle(title string) {
	provider.current = title
	if provider.onTitle != nil {
		provider.onTitle(title)
	}
}

func (provider *terminalTitleProvider) PushTitle() {
	provider.stack = append(provider.stack, provider.current)
}

func (provider *terminalTitleProvider) PopTitle() {
	if len(provider.stack) == 0 {
		return
	}
	last := len(provider.stack) - 1
	title := provider.stack[last]
	provider.stack = provider.stack[:last]
	provider.SetTitle(title)
}
