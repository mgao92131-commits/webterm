package terminalsession

import "webterm/go-core/internal/terminalengine"

// engineSignals 是一次 terminal engine 变更期间同步产生的内部信号。
//
// Runtime 的 events inbox 只允许 actor 外部的生产者写入；engine 回调与
// Runtime actor 在同一个 goroutine 中执行，因此只能记录到这里，不能再次
// postEvent 给 actor 自己。每次 engine 变更完成后由 Runtime 统一提交。
type engineSignals struct {
	historyTrimFirstSeq uint64
	effects             []terminalengine.Effect
}

type engineSignalBatch struct {
	historyTrimFirstSeq uint64
	effects             []terminalengine.Effect
}

func (s *engineSignals) recordHistoryTrim(firstSeq uint64) {
	// HistoryTrim 是单调水位，同一轮只需要向客户端提交最新值。
	if firstSeq > s.historyTrimFirstSeq {
		s.historyTrimFirstSeq = firstSeq
	}
}

func (s *engineSignals) recordEffect(effect terminalengine.Effect) {
	s.effects = append(s.effects, effect)
}

func (s *engineSignals) drain() engineSignalBatch {
	batch := engineSignalBatch{
		historyTrimFirstSeq: s.historyTrimFirstSeq,
		effects:             s.effects,
	}
	s.historyTrimFirstSeq = 0
	s.effects = nil
	return batch
}
