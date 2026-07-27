package screenprojection

import (
	"webterm/go-core/internal/historysegment"
	"webterm/go-core/internal/terminalengine"
)

// ExportedHistorySegment 是封存段导出为传输无关 Line + message-local 字典。
type ExportedHistorySegment struct {
	Lines  []terminalengine.Line
	Styles []terminalengine.TerminalStyle
	Links  []terminalengine.Hyperlink
}

// ExportHistorySegment 把不可变 Segment 导出为 LineData 所需的中间表示。
// 不触及可变 scrollback，可在 HTTP 路径并发调用。
func ExportHistorySegment(seg *historysegment.Segment) ExportedHistorySegment {
	if seg == nil {
		return ExportedHistorySegment{}
	}
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	lines := make([]terminalengine.Line, len(seg.Lines))
	for i, line := range seg.Lines {
		lines[i] = exp.exportScrollbackEntry(terminalengine.ScrollbackEntry{
			HistorySeq: line.HistorySeq,
			LineID:     line.LineID,
			Version:    line.Version,
			Wrapped:    line.Wrapped,
			Cells:      line.Cells,
		})
	}
	return ExportedHistorySegment{
		Lines:  lines,
		Styles: exp.styleTable.Styles(),
		Links:  exp.linkTable.Links(),
	}
}
