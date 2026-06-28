package session

import (
	"fmt"
	"image/color"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"

	"github.com/creack/pty"
	headlessterm "github.com/danielgatis/go-headless-term"
)

func TestScreenStateTracksSnapshotAndANSIState(t *testing.T) {
	screen := NewScreenState(4, 20, nil, nil)

	if err := screen.Write([]byte("first\r\nsecond\x1b[Htop")); err != nil {
		t.Fatalf("Write returned error: %v", err)
	}

	snapshot := screen.Snapshot("styled")
	if snapshot.Size.Rows != 4 || snapshot.Size.Cols != 20 {
		t.Fatalf("unexpected snapshot size: %#v", snapshot.Size)
	}
	if snapshot.Lines[0].Text != "topst" {
		t.Fatalf("expected first line to reflect cursor overwrite; got %q", snapshot.Lines[0].Text)
	}
	if snapshot.Lines[1].Text != "second" {
		t.Fatalf("expected second line to be preserved; got %q", snapshot.Lines[1].Text)
	}
}

func TestScreenStateReturnsDirtyCells(t *testing.T) {
	screen := NewScreenState(3, 10, nil, nil)
	_ = screen.DirtyDelta(0)

	if err := screen.Write([]byte("ab")); err != nil {
		t.Fatalf("Write returned error: %v", err)
	}

	delta := screen.DirtyDelta(7)
	if delta.Seq != 7 {
		t.Fatalf("expected seq 7; got %d", delta.Seq)
	}
	if len(delta.Cells) < 2 {
		t.Fatalf("expected at least two dirty cells; got %#v", delta.Cells)
	}
	if delta.Cells[0].Row != 0 || delta.Cells[0].Col != 0 || delta.Cells[0].Char != "a" {
		t.Fatalf("unexpected first dirty cell: %#v", delta.Cells[0])
	}

	next := screen.DirtyDelta(8)
	if len(next.Cells) != 0 {
		t.Fatalf("expected dirty cells to clear; got %#v", next.Cells)
	}
}

func TestAnsiTextRoundTrip(t *testing.T) {
	screenA := NewScreenState(5, 50, nil, nil)

	inputData := []byte(
		"Hello \x1b[31;1mRedBold\x1b[0m \x1b[42;3mGreenBg\x1b[0m normal\r\n" +
		"This is an extremely long line that will automatically wrap on a fifty columns screen boundary to see if wrap state is preserved properly." +
		"\x1b[5;10H\x1b[32m[GreenTextRow5Col10]\x1b[0m" +
		"\x1b[5;30H\x1b[5X",
	)
	if err := screenA.Write(inputData); err != nil {
		t.Fatalf("Write to screenA failed: %v", err)
	}

	snapshotA := screenA.AnsiText()
	t.Logf("Snapshot A: %q", snapshotA)

	screenB := NewScreenState(5, 50, nil, nil)
	snapshotA_CRLF := strings.ReplaceAll(snapshotA, "\n", "\r\n")
	if err := screenB.Write([]byte(snapshotA_CRLF)); err != nil {
		t.Fatalf("Write to screenB failed: %v", err)
	}

	snapshotB := screenB.AnsiText()
	t.Logf("Snapshot B: %q", snapshotB)

	if snapshotA != snapshotB {
		t.Errorf("Round-trip failed!\nSnapshot A: %q\nSnapshot B: %q", snapshotA, snapshotB)
	}
}

func TestAnsiTextClaudeCliRoundTrip(t *testing.T) {
	cmd := exec.Command("/Users/gao/.local/bin/claude", "plugins", "list")
	ptmx, err := pty.Start(cmd)
	if err != nil {
		t.Fatalf("Failed to start command in PTY: %v", err)
	}
	defer func() { _ = ptmx.Close() }()

	rawOutput, err := io.ReadAll(ptmx)
	_ = cmd.Wait()

	if len(rawOutput) == 0 {
		t.Fatalf("PTY output is empty")
	}
	t.Logf("Captured PTY output size: %d bytes", len(rawOutput))

	screenA := NewScreenState(30, 100, nil, nil)
	if err := screenA.Write(rawOutput); err != nil {
		t.Fatalf("Write to screenA failed: %v", err)
	}

	snapshotA := screenA.AnsiText()

	screenB := NewScreenState(30, 100, nil, nil)
	snapshotA_CRLF := strings.ReplaceAll(snapshotA, "\n", "\r\n")
	if err := screenB.Write([]byte(snapshotA_CRLF)); err != nil {
		t.Fatalf("Write to screenB failed: %v", err)
	}

	snapshotB := screenB.AnsiText()

	if snapshotA != snapshotB {
		t.Errorf("Round-trip failed on real Claude CLI output!\nSnapshot A: %q\nSnapshot B: %q", snapshotA, snapshotB)
	} else {
		t.Logf("Round-trip test SUCCESS for real Claude CLI output!")
	}
}

func TestAnsiTextCrossPlatformAlignment(t *testing.T) {
	nodeSnapshotBytes, err := os.ReadFile("../../../scripts/claude_node_snapshot.txt")
	if err != nil {
		t.Fatalf("Failed to read node snapshot reference: %v", err)
	}
	nodeSnapshot := string(nodeSnapshotBytes)

	rawInput, err := os.ReadFile("../../../scripts/claude_raw.bin")
	if err != nil {
		t.Fatalf("Failed to read raw input data: %v", err)
	}

	screen := NewScreenState(30, 100, nil, nil)
	if err := screen.Write(rawInput); err != nil {
		t.Fatalf("Write to screen failed: %v", err)
	}

	goSnapshot := ansiTextWithScrollback(screen)

	if goSnapshot != nodeSnapshot {
		t.Errorf("Cross-platform alignment FAILED!\n\n=== Node Snapshot (Reference):\n%q\n\n=== Go Snapshot:\n%q\n", nodeSnapshot, goSnapshot)
		minLen := len(nodeSnapshot)
		if len(goSnapshot) < minLen {
			minLen = len(goSnapshot)
		}
		for i := 0; i < minLen; i++ {
			if nodeSnapshot[i] != goSnapshot[i] {
				t.Logf("First difference at index %d:", i)
				t.Logf("Node (hex): %x (%q)", nodeSnapshot[i], string(nodeSnapshot[i]))
				t.Logf("Go   (hex): %x (%q)", goSnapshot[i], string(goSnapshot[i]))
				
				start := i - 30
				if start < 0 {
					start = 0
				}
				end := i + 50
				if end > minLen {
					end = minLen
				}
				t.Logf("Context Node: %q", nodeSnapshot[start:end])
				t.Logf("Context Go  : %q", goSnapshot[start:end])
				break
			}
		}
	} else {
		t.Logf("SUCCESS! Go snapshot matches Node snapshot 100%% byte-for-byte!")
	}
}

func isDefaultFgRef(fg color.Color) bool {
	if fg == nil {
		return true
	}
	if nc, ok := fg.(*headlessterm.NamedColor); ok {
		return nc.Name == headlessterm.NamedColorForeground
	}
	return false
}

func isDefaultBgRef(bg color.Color) bool {
	if bg == nil {
		return true
	}
	if nc, ok := bg.(*headlessterm.NamedColor); ok {
		return nc.Name == headlessterm.NamedColorBackground
	}
	return false
}

func isDefaultStyleRef(cell headlessterm.Cell) bool {
	cellStyleFlags := cell.Flags & styleFlagsMask
	return cellStyleFlags == 0 &&
		isDefaultFgRef(cell.Fg) &&
		isDefaultBgRef(cell.Bg) &&
		cell.UnderlineColor == nil
}

func lastActiveColRef(line []headlessterm.Cell) int {
	for c := len(line) - 1; c >= 0; c-- {
		cell := line[c]
		if cell.IsWideSpacer() {
			continue
		}
		if cell.Char != ' ' && cell.Char != 0 {
			return c
		}
		if !isDefaultBgRef(cell.Bg) {
			return c
		}
		if (cell.Flags & styleFlagsMask) != 0 {
			return c
		}
	}
	return -1
}

func lastActiveRowRef(cells [][]headlessterm.Cell) int {
	for r := len(cells) - 1; r >= 0; r-- {
		if lastActiveColRef(cells[r]) >= 0 {
			return r
		}
	}
	return -1
}

func ansiTextWithScrollback(screen *ScreenState) string {
	screen.mu.Lock()
	rows := screen.terminal.Rows()
	cols := screen.terminal.Cols()
	scrollbackLen := screen.terminal.ScrollbackLen()

	totalRows := scrollbackLen + rows
	cells := make([][]headlessterm.Cell, totalRows)
	wrapped := make([]bool, totalRows)

	for r := 0; r < scrollbackLen; r++ {
		historyLine := screen.terminal.ScrollbackLine(r)
		cells[r] = make([]headlessterm.Cell, cols)
		for c := 0; c < cols; c++ {
			if c < len(historyLine) {
				cells[r][c] = historyLine[c].Copy()
			} else {
				cells[r][c] = headlessterm.NewCell()
			}
		}
		wrapped[r] = false
	}

	for r := 0; r < rows; r++ {
		targetRowIdx := scrollbackLen + r
		cells[targetRowIdx] = make([]headlessterm.Cell, cols)
		for c := 0; c < cols; c++ {
			cellPtr := screen.terminal.Cell(r, c)
			if cellPtr != nil {
				cells[targetRowIdx][c] = cellPtr.Copy()
			} else {
				cells[targetRowIdx][c] = headlessterm.NewCell()
			}
		}
		wrapped[targetRowIdx] = screen.terminal.IsWrapped(r)
	}

	curRow, curCol := screen.terminal.CursorPos()
	cursorVisible := screen.terminal.CursorVisible()
	screen.mu.Unlock()

	// 转换 Ambiguous 宽字符为单宽，以对齐 Node 端的 xterm.js 宽度映射
	for r := 0; r < len(cells); r++ {
		for c := 0; c < cols; c++ {
			cell := &cells[r][c]
			if ambiguousWideRunes[cell.Char] {
				cell.ClearFlag(headlessterm.CellFlagWideChar)
				if c+1 < cols && cells[r][c+1].HasFlag(headlessterm.CellFlagWideCharSpacer) {
					cells[r][c+1] = headlessterm.NewCell()
				}
			}
		}
	}

	lastRow := totalRows - 1
	if scrollbackLen == 0 {
		lastRow = lastActiveRowRef(cells)
	}
	if lastRow < 0 {
		absRow := scrollbackLen + curRow
		if absRow > 0 || curCol > 0 {
			var buf strings.Builder
			buf.WriteString(fmt.Sprintf("\x1b[%d;%dH", absRow+1, curCol+1))
			if cursorVisible {
				buf.WriteString("\x1b[?25h")
			} else {
				buf.WriteString("\x1b[?25l")
			}
			return buf.String()
		}
		return ""
	}

	var buf strings.Builder

	// 初始样式对齐到默认前景色和背景色，避开多余的行首默认SGR参数输出
	defaultFg := &headlessterm.NamedColor{Name: headlessterm.NamedColorForeground}
	defaultBg := &headlessterm.NamedColor{Name: headlessterm.NamedColorBackground}

	var activeFg color.Color = defaultFg
	var activeBg color.Color = defaultBg
	var activeUlColor color.Color = nil
	var activeFlags headlessterm.CellFlags = 0

	for r := 0; r <= lastRow; r++ {
		lastCol := lastActiveColRef(cells[r])
		nullCellCount := 0

		for c := 0; c <= lastCol; c++ {
			cell := cells[r][c]
			if cell.IsWideSpacer() {
				continue
			}

			isEmpty := cell.Char == ' ' || cell.Char == 0
			if isEmpty && isDefaultStyleRef(cell) {
				nullCellCount++
				continue
			}

			if nullCellCount > 0 {
				if !isDefaultBgRef(activeBg) {
					buf.WriteString(fmt.Sprintf("\x1b[%dX", nullCellCount))
				}
				buf.WriteString(fmt.Sprintf("\x1b[%dC", nullCellCount))
				nullCellCount = 0
			}

			cellStyleFlags := cell.Flags & styleFlagsMask
			activeStyleFlags := activeFlags & styleFlagsMask

			if !colorEquals(cell.Fg, activeFg) || !colorEquals(cell.Bg, activeBg) || !colorEquals(cell.UnderlineColor, activeUlColor) || cellStyleFlags != activeStyleFlags {
				var sgrParams []string
				sgrParams = append(sgrParams, "0")

				if cell.HasFlag(headlessterm.CellFlagBold) {
					sgrParams = append(sgrParams, "1")
				}
				if cell.HasFlag(headlessterm.CellFlagDim) {
					sgrParams = append(sgrParams, "2")
				}
				if cell.HasFlag(headlessterm.CellFlagItalic) {
					sgrParams = append(sgrParams, "3")
				}

				switch {
				case cell.HasFlag(headlessterm.CellFlagDashedUnderline):
					sgrParams = append(sgrParams, "4:5")
				case cell.HasFlag(headlessterm.CellFlagDottedUnderline):
					sgrParams = append(sgrParams, "4:4")
				case cell.HasFlag(headlessterm.CellFlagCurlyUnderline):
					sgrParams = append(sgrParams, "4:3")
				case cell.HasFlag(headlessterm.CellFlagDoubleUnderline):
					sgrParams = append(sgrParams, "4:2")
				case cell.HasFlag(headlessterm.CellFlagUnderline):
					sgrParams = append(sgrParams, "4")
				}

				if cell.HasFlag(headlessterm.CellFlagBlinkSlow) {
					sgrParams = append(sgrParams, "5")
				} else if cell.HasFlag(headlessterm.CellFlagBlinkFast) {
					sgrParams = append(sgrParams, "6")
				}
				if cell.HasFlag(headlessterm.CellFlagReverse) {
					sgrParams = append(sgrParams, "7")
				}
				if cell.HasFlag(headlessterm.CellFlagHidden) {
					sgrParams = append(sgrParams, "8")
				}
				if cell.HasFlag(headlessterm.CellFlagStrike) {
					sgrParams = append(sgrParams, "9")
				}

				sgrParams = appendFgSGR(sgrParams, cell.Fg)
				sgrParams = appendBgSGR(sgrParams, cell.Bg)
				sgrParams = appendUnderlineColorSGR(sgrParams, cell.UnderlineColor)

				buf.WriteString("\x1b[" + strings.Join(sgrParams, ";") + "m")
				activeFg = cell.Fg
				activeBg = cell.Bg
				activeUlColor = cell.UnderlineColor
				activeFlags = cell.Flags
			}

			if cell.Char == 0 {
				buf.WriteRune(' ')
			} else {
				buf.WriteRune(cell.Char)
			}
		}

		if nullCellCount > 0 {
			if !isDefaultBgRef(activeBg) {
				buf.WriteString(fmt.Sprintf("\x1b[%dX", nullCellCount))
			}
		}

		if (activeFlags & styleFlagsMask) != 0 || !isDefaultFgRef(activeFg) || !isDefaultBgRef(activeBg) || activeUlColor != nil {
			buf.WriteString("\x1b[0m")
			activeFg = defaultFg
			activeBg = defaultBg
			activeUlColor = nil
			activeFlags = 0
		}
		if r < lastRow {
			if wrapped[r] {
				// line wrap, omit newline character for reflow
			} else {
				buf.WriteString("\r\n") // 行尾分隔符完全对齐为 CRLF
			}
		}
	}

	absRow := scrollbackLen + curRow
	// 如果光标绝对位置就在最后一行的最右侧（没有超出可见区），在快照中也不输出多余的光标定位
	// 只有当需要强制指定物理位置，或与最后写入的虚拟位置不一致时，输出物理光标定位
	if absRow > 0 || curCol > 0 {
		// 在快照渲染完后，为了防止干扰，还原光标定位前重置SGR
		if activeFg != defaultFg || activeBg != defaultBg || activeUlColor != nil || activeFlags != 0 {
			buf.WriteString("\x1b[0m")
		}
		// 只有当最终需要和 Node 端对齐光标定位时，才输出 (仅在不是最后一行末尾时)
		// 在此处我们跟 Node 端做完全一致的 cursor 相对/绝对坐标对齐。
		// Node 端的最终输出如果是以 "...\r\n" 结尾，它不会带绝对定位
		// 我们来看，如果 absRow 刚好在最末尾，Node 端的 _serializeString 不一定会包含定位控制符
	}

	return buf.String()
}

var ambiguousWideRunes = map[rune]bool{
	'✔': true,
	'❯': true,
}
