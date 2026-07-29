package screenprotocolv2

import "webterm/go-core/internal/terminalengine"

// BaselineValidationError 是服务端自校验的稳定错误；Error 不包含终端正文。
type BaselineValidationError struct {
	Code string
}

func (e *BaselineValidationError) Error() string {
	if e == nil || e.Code == "" {
		return "baseline validation failed"
	}
	return "baseline validation failed: " + e.Code
}

func baselineFault(code string) error {
	return &BaselineValidationError{Code: code}
}

func validateBaselineFrame(frame terminalengine.ScreenFrame) error {
	if frame.SessionID == "" || frame.InstanceID == "" || frame.Epoch == 0 {
		return baselineFault("INVALID_IDENTITY")
	}
	if frame.Seq == 0 || frame.DictionaryGeneration == 0 || frame.HistoryGeneration == 0 {
		return baselineFault("INVALID_GENERATION")
	}
	if frame.Rows < 1 || frame.Rows > 200 || frame.Cols < 1 || frame.Cols > 500 {
		return baselineFault("INVALID_GEOMETRY")
	}
	if frame.Kind != terminalengine.FrameSnapshot {
		return baselineFault("MODEL_REJECTED_BASELINE")
	}
	if len(frame.Screen) != frame.Rows {
		return baselineFault("SCREEN_LINE_COUNT_MISMATCH")
	}

	styleIDs := make(map[uint32]struct{}, len(frame.Styles))
	if len(frame.Styles) > 4096 {
		return baselineFault("INVALID_DICTIONARY")
	}
	for _, style := range frame.Styles {
		if style.ID == 0 {
			return baselineFault("INVALID_DICTIONARY")
		}
		if _, exists := styleIDs[style.ID]; exists {
			return baselineFault("INVALID_DICTIONARY")
		}
		styleIDs[style.ID] = struct{}{}
	}
	linkIDs := make(map[uint32]struct{}, len(frame.Links))
	if len(frame.Links) > 4096 {
		return baselineFault("INVALID_DICTIONARY")
	}
	for _, link := range frame.Links {
		if link.ID == 0 {
			return baselineFault("INVALID_DICTIONARY")
		}
		if _, exists := linkIDs[link.ID]; exists {
			return baselineFault("INVALID_DICTIONARY")
		}
		linkIDs[link.ID] = struct{}{}
	}

	type lineKey struct {
		id      uint64
		version uint64
	}
	active := make(map[lineKey]struct{}, len(frame.Screen))
	for _, line := range frame.Screen {
		if line.ID == 0 || line.Version == 0 || line.HistorySeq != 0 {
			return baselineFault("INVALID_LINE_BODY")
		}
		if line.PhysicalColumns != frame.Cols {
			return baselineFault("LINE_COLUMN_COUNT_MISMATCH")
		}
		key := lineKey{id: line.ID, version: line.Version}
		if _, exists := active[key]; exists {
			return baselineFault("DUPLICATE_ACTIVE_KEY")
		}
		active[key] = struct{}{}
		if err := validateBaselineLine(line, styleIDs, linkIDs); err != nil {
			return err
		}
	}

	first := frame.History.FirstAvailableHistorySeq
	last := frame.History.LastIncludedHistorySeq
	// 备用屏没有 scrollback，零值窗口会在线上编码为 proto3 的 0..0；
	// 主屏空历史使用规范化的 1..0。
	zeroExtent := first == 0 && last == 0
	if (!zeroExtent && first == 0) || last == ^uint64(0) || (!zeroExtent && first > last+1) {
		return baselineFault("HISTORY_BINDING_COUNT_MISMATCH")
	}
	expected := uint64(0)
	if !zeroExtent && last >= first {
		expected = last - first + 1
	}
	if uint64(len(frame.ScrollbackLineage)) != expected {
		return baselineFault("HISTORY_BINDING_COUNT_MISMATCH")
	}
	history := make(map[lineKey]struct{}, len(frame.ScrollbackLineage))
	var previous uint64
	for _, binding := range frame.ScrollbackLineage {
		if binding.HistorySeq <= previous {
			return baselineFault("HISTORY_SEQ_OUT_OF_ORDER")
		}
		if binding.HistorySeq < first || binding.HistorySeq > last {
			return baselineFault("HISTORY_SEQ_OUT_OF_EXTENT")
		}
		if binding.LineID == 0 || binding.LineVersion == 0 {
			return baselineFault("INVALID_LINE_BODY")
		}
		key := lineKey{id: binding.LineID, version: binding.LineVersion}
		if _, exists := history[key]; exists {
			return baselineFault("DUPLICATE_HISTORY_KEY")
		}
		if _, exists := active[key]; exists {
			return baselineFault("ACTIVE_HISTORY_KEY_CONFLICT")
		}
		history[key] = struct{}{}
		previous = binding.HistorySeq
	}
	return nil
}

func validateBaselineLine(
	line terminalengine.Line,
	styleIDs map[uint32]struct{},
	linkIDs map[uint32]struct{},
) error {
	previousEnd := 0
	for _, run := range line.Runs {
		if run.Col < previousEnd || run.Col < 0 || run.Col >= line.PhysicalColumns {
			return baselineFault("INVALID_LINE_BODY")
		}
		col := run.Col
		for _, cell := range run.Cells {
			width := int(cell.Width)
			if width != 1 && width != 2 {
				return baselineFault("INVALID_LINE_BODY")
			}
			if col+width > line.PhysicalColumns {
				return baselineFault("LINE_COLUMN_COUNT_MISMATCH")
			}
			if cell.StyleID != 0 {
				if _, exists := styleIDs[cell.StyleID]; !exists {
					return baselineFault("INVALID_DICTIONARY")
				}
			}
			if cell.LinkID != 0 {
				if _, exists := linkIDs[cell.LinkID]; !exists {
					return baselineFault("INVALID_DICTIONARY")
				}
			}
			col += width
		}
		previousEnd = col
	}
	return nil
}
