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

// SetActiveScreen 替换当前活动 buffer 的行键布局。
func (s *CanonicalTerminalState) SetActiveScreen(rows []LineKey) {
	if s == nil {
		return
	}
	if s.ActiveBuffer == BufferAlternate {
		s.AlternateScreen = rows
		return
	}
	s.MainScreen = rows
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
