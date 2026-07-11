package screenprojection

import (
	"webterm/go-core/internal/terminalengine"
)

// HistoryView 提供分页历史查询。
type HistoryView struct {
	scrollback *terminalengine.TrackedScrollback
}

// NewHistoryView 创建历史视图。
func NewHistoryView(scrollback *terminalengine.TrackedScrollback) *HistoryView {
	return &HistoryView{scrollback: scrollback}
}

// Page 返回严格小于 beforeID 的最多 limit 行，按 ID 升序。
func (v *HistoryView) Page(beforeID uint64, limit int) terminalengine.HistoryWindow {
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	return v.pageWithExporter(beforeID, limit, exp)
}

func (v *HistoryView) pageWithExporter(beforeID uint64, limit int, exp *exporter) terminalengine.HistoryWindow {
	if limit <= 0 {
		limit = 250
	}
	if limit > 500 {
		limit = 500
	}

	firstAvailable := v.scrollback.FirstID()
	if beforeID <= firstAvailable {
		return terminalengine.HistoryWindow{
			FirstAvailableLineID: firstAvailable,
			FirstIncludedLineID:  firstAvailable,
			LastIncludedLineID:   firstAvailable - 1,
			HasMoreBefore:        false,
			Lines:                nil,
		}
	}

	lines := v.scrollback.PageBefore(beforeID, limit)
	if len(lines) == 0 {
		return terminalengine.HistoryWindow{
			FirstAvailableLineID: firstAvailable,
			FirstIncludedLineID:  beforeID,
			LastIncludedLineID:   beforeID - 1,
			HasMoreBefore:        false,
			Lines:                nil,
		}
	}

	exported := make([]terminalengine.Line, len(lines))
	for i, hl := range lines {
		exported[i] = terminalengine.Line{
			ID:      hl.ID,
			Row:     -1,
			Wrapped: hl.Wrapped,
			Runs:    exp.exportHistoryCells(hl.Cells),
		}
	}

	return terminalengine.HistoryWindow{
		FirstAvailableLineID: firstAvailable,
		FirstIncludedLineID:  exported[0].ID,
		LastIncludedLineID:   exported[len(exported)-1].ID,
		HasMoreBefore:        exported[0].ID > firstAvailable,
		Lines:                exported,
	}
}
