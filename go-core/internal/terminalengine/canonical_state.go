package terminalengine

// CanonicalTerminalState 是屏幕布局与历史位置对 LineKey 的权威引用层。
// 正文只存在于 LineStore；Screen/History 只移动 LineKey。
type CanonicalTerminalState struct {
	Revision uint64

	LineStore *LineStore

	MainScreen      []LineKey
	AlternateScreen []LineKey

	HistoryExtent HistoryExtent
	History       map[uint64]LineKey

	ActiveBuffer BufferKind
	Cursor       Cursor
	Modes        Modes
}

// NewCanonicalTerminalState 创建带空 LineStore 的权威状态。
func NewCanonicalTerminalState() *CanonicalTerminalState {
	return &CanonicalTerminalState{
		LineStore: NewLineStore(),
		History:   make(map[uint64]LineKey),
		HistoryExtent: HistoryExtent{
			FirstSeq: 1,
			LastSeq:  0,
		},
	}
}

// ActiveScreen 返回当前活动 buffer 的行键切片（可写别名）。
func (s *CanonicalTerminalState) ActiveScreen() []LineKey {
	if s == nil {
		return nil
	}
	if s.ActiveBuffer == BufferAlternate {
		return s.AlternateScreen
	}
	return s.MainScreen
}

// SetActiveScreen 替换当前活动 buffer 的行键布局，并维护 LineStore StateRefs。
func (s *CanonicalTerminalState) SetActiveScreen(rows []LineKey) {
	if s == nil {
		return
	}
	s.ReplaceActiveScreen(rows)
}

// ReplaceActiveScreen 原子替换活动屏布局，并对新旧 key 做引用计数差分。
func (s *CanonicalTerminalState) ReplaceActiveScreen(next []LineKey) {
	if s == nil {
		return
	}
	previous := s.ActiveScreen()
	remove, add := refDelta(previous, next)
	if s.LineStore != nil {
		s.LineStore.ApplyStateRefDelta(add, remove)
	}
	copied := append([]LineKey(nil), next...)
	if s.ActiveBuffer == BufferAlternate {
		s.AlternateScreen = copied
		return
	}
	s.MainScreen = copied
}

// ReplaceActiveScreenDirty 只对已变化的行提交 screen 引用差分。
// 调用方必须保证 active buffer 未切换且 next 与当前屏幕长度一致。
func (s *CanonicalTerminalState) ReplaceActiveScreenDirty(
	next []LineKey, changedRows []int) {
	if s == nil {
		return
	}
	previous := s.ActiveScreen()
	if len(previous) != len(next) {
		s.ReplaceActiveScreen(next)
		return
	}
	remove := make([]LineKey, 0, len(changedRows))
	add := make([]LineKey, 0, len(changedRows))
	for _, row := range changedRows {
		if row < 0 || row >= len(next) || previous[row] == next[row] {
			continue
		}
		if previous[row].ID != 0 {
			remove = append(remove, previous[row])
		}
		if next[row].ID != 0 {
			add = append(add, next[row])
		}
	}
	if s.LineStore != nil {
		s.LineStore.ApplyStateRefDelta(add, remove)
	}
	copied := append([]LineKey(nil), next...)
	if s.ActiveBuffer == BufferAlternate {
		s.AlternateScreen = copied
	} else {
		s.MainScreen = copied
	}
}

func refDelta(previous, next []LineKey) (remove, add []LineKey) {
	delta := make(map[LineKey]int, len(previous)+len(next))
	for _, key := range previous {
		if key.ID != 0 {
			delta[key]--
		}
	}
	for _, key := range next {
		if key.ID != 0 {
			delta[key]++
		}
	}
	for key, change := range delta {
		if change < 0 {
			for i := 0; i < -change; i++ {
				remove = append(remove, key)
			}
		} else {
			for i := 0; i < change; i++ {
				add = append(add, key)
			}
		}
	}
	return remove, add
}

// BindHistory 将 HistorySeq 绑定到 LineKey，并维护 StateRefs。
func (s *CanonicalTerminalState) BindHistory(seq uint64, key LineKey) {
	if s == nil || s.LineStore == nil || seq == 0 || key.ID == 0 {
		return
	}
	if previous, ok := s.History[seq]; ok {
		if previous == key {
			return
		}
		s.LineStore.ReleaseStateRef(previous)
	}
	s.History[seq] = key
	s.LineStore.AddStateRef(key)
}

// UnbindHistory 移除 HistorySeq 绑定。
func (s *CanonicalTerminalState) UnbindHistory(seq uint64) {
	if s == nil || s.LineStore == nil {
		return
	}
	if previous, ok := s.History[seq]; ok {
		s.LineStore.ReleaseStateRef(previous)
		delete(s.History, seq)
	}
}

// TrimHistoryBefore 丢弃 firstSeq 之前的历史绑定。
func (s *CanonicalTerminalState) TrimHistoryBefore(firstSeq uint64) {
	if s == nil {
		return
	}
	for seq := range s.History {
		if seq < firstSeq {
			s.UnbindHistory(seq)
		}
	}
	s.HistoryExtent.FirstSeq = firstSeq
}
