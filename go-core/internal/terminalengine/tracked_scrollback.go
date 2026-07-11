package terminalengine

import (
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// HistoryLine 是带稳定 ID 的历史行，由 TrackedScrollback 维护。
type HistoryLine struct {
	ID      uint64
	Wrapped bool
	Cells   []headlessterm.Cell
}

// ScrollbackTrimEvent 在历史行因容量限制被丢弃时触发。
type ScrollbackTrimEvent struct {
	FirstAvailableID uint64
}

// TrackedScrollback 是 headless-term 的唯一 scrollback provider。
// 它在同一 layout epoch 内为每一行分配单调递增的 ID，并在 trim 时通知调用方。
type TrackedScrollback struct {
	mu       sync.RWMutex
	capacity int

	layoutEpoch uint64
	firstID     uint64
	nextID      uint64
	lines       []HistoryLine

	onTrim func(ScrollbackTrimEvent)
}

// NewTrackedScrollback 创建容量为 capacity 的可跟踪 scrollback。
func NewTrackedScrollback(capacity int, onTrim func(ScrollbackTrimEvent)) *TrackedScrollback {
	if capacity <= 0 {
		capacity = 10000
	}
	return &TrackedScrollback{
		capacity: capacity,
		onTrim:   onTrim,
		firstID:  1,
		nextID:   1,
	}
}

// SetLayoutEpoch 在 resize/reflow 导致旧物理行全部失效时调用。
// 这会清空历史并重新从 1 开始分配 ID。
func (t *TrackedScrollback) SetLayoutEpoch(epoch uint64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.layoutEpoch = epoch
	t.firstID = 1
	t.nextID = 1
	t.lines = t.lines[:0]
}

// LayoutEpoch 返回当前 layout epoch。
func (t *TrackedScrollback) LayoutEpoch() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.layoutEpoch
}

// Push 追加一行到历史。
func (t *TrackedScrollback) Push(line headlessterm.ScrollbackLine) {
	t.mu.Lock()
	defer t.mu.Unlock()

	cells := make([]headlessterm.Cell, len(line.Cells))
	copy(cells, line.Cells)

	t.lines = append(t.lines, HistoryLine{
		ID:      t.nextID,
		Wrapped: line.Wrapped,
		Cells:   cells,
	})
	t.nextID++

	if len(t.lines) > t.capacity {
		excess := len(t.lines) - t.capacity
		t.lines = t.lines[excess:]
		t.firstID = t.lines[0].ID
		t.fireTrimLocked()
	}
}

// Pop 移除并返回最新一行。
func (t *TrackedScrollback) Pop() headlessterm.ScrollbackLine {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.lines) == 0 {
		return headlessterm.ScrollbackLine{}
	}
	line := t.lines[len(t.lines)-1]
	t.lines = t.lines[:len(t.lines)-1]
	if len(t.lines) > 0 {
		t.firstID = t.lines[0].ID
	} else {
		t.firstID = t.nextID
	}
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped}
}

// Len 返回当前历史行数。
func (t *TrackedScrollback) Len() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return len(t.lines)
}

// Line 按索引（0=最老）返回历史行。
func (t *TrackedScrollback) Line(index int) headlessterm.ScrollbackLine {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if index < 0 || index >= len(t.lines) {
		return headlessterm.ScrollbackLine{}
	}
	line := t.lines[index]
	return headlessterm.ScrollbackLine{Cells: line.Cells, Wrapped: line.Wrapped}
}

// LineByID 按 line ID 返回历史行；找不到返回 ok=false。
func (t *TrackedScrollback) LineByID(id uint64) (HistoryLine, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if id < t.firstID || id >= t.nextID {
		return HistoryLine{}, false
	}
	// ID 单调递增且与切片下标一一对应。
	index := int(id - t.firstID)
	if index >= len(t.lines) {
		return HistoryLine{}, false
	}
	return t.lines[index], true
}

// PageBefore 返回严格小于 beforeID 的最多 limit 行，按 ID 升序。
func (t *TrackedScrollback) PageBefore(beforeID uint64, limit int) []HistoryLine {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if limit <= 0 || len(t.lines) == 0 || beforeID <= t.firstID {
		return nil
	}
	end := len(t.lines)
	if beforeID < t.nextID {
		end = int(beforeID - t.firstID)
	}
	start := end - limit
	if start < 0 {
		start = 0
	}
	result := make([]HistoryLine, end-start)
	copy(result, t.lines[start:end])
	return result
}

// FirstID 返回最老可用行 ID。
func (t *TrackedScrollback) FirstID() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.firstID
}

// NextID 返回下一个将分配的 ID。
func (t *TrackedScrollback) NextID() uint64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.nextID
}

// Clear 清空历史。
func (t *TrackedScrollback) Clear() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.lines = t.lines[:0]
	t.firstID = 1
	t.nextID = 1
	t.fireTrimLocked()
}

// SetMaxLines 调整容量。
func (t *TrackedScrollback) SetMaxLines(max int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.capacity = max
	if max > 0 && len(t.lines) > max {
		excess := len(t.lines) - max
		t.lines = t.lines[excess:]
		t.firstID = t.lines[0].ID
		t.fireTrimLocked()
	}
}

// MaxLines 返回容量。
func (t *TrackedScrollback) MaxLines() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.capacity
}

func (t *TrackedScrollback) fireTrimLocked() {
	if t.onTrim != nil {
		t.onTrim(ScrollbackTrimEvent{FirstAvailableID: t.firstID})
	}
}
