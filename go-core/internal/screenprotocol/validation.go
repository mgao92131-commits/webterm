package screenprotocol

import (
	"fmt"
	"unicode/utf8"

	pb "webterm/go-core/internal/screenprotocol/generated"
)

const (
	minRows            = 5
	maxRows            = 200
	minCols            = 10
	maxCols            = 500
	maxHistoryPage     = 500
	maxSnapshotHistory = 500
	maxEnvelopeBytes   = 2 * 1024 * 1024
	maxTitleBytes      = 4096
	maxCWDBytes        = 4096
	maxURIBytes        = 8192
	maxCellTextBytes   = 64
)

// ValidateEnvelopeSize 校验 envelope 总大小。
func ValidateEnvelopeSize(data []byte) error {
	if len(data) > maxEnvelopeBytes {
		return fmt.Errorf("envelope too large: %d > %d", len(data), maxEnvelopeBytes)
	}
	return nil
}

// ValidateHello 校验 hello 消息。
func ValidateHello(h *pb.Hello) error {
	if h.Version != 1 {
		return fmt.Errorf("unsupported hello version: %d", h.Version)
	}
	if h.Cols > 0 && (h.Cols < minCols || h.Cols > maxCols) {
		return fmt.Errorf("invalid cols: %d", h.Cols)
	}
	if h.Rows > 0 && (h.Rows < minRows || h.Rows > maxRows) {
		return fmt.Errorf("invalid rows: %d", h.Rows)
	}
	// 恢复 token 一致性（计划 §3.5）：无投影时 token 字段必须全部为默认值；
	// 有投影时 token 必须完整且客户端声明支持 row patch。选择拒绝而非忽略：
	// 现有客户端都发干净 Hello，拒绝更确定、可测。
	if !h.HasProjection {
		if h.InstanceId != "" || h.LayoutEpoch != 0 || h.ScreenRevision != 0 {
			return fmt.Errorf("hello without projection must not carry resume token")
		}
		return nil
	}
	if h.InstanceId == "" {
		return fmt.Errorf("hello with projection missing instance id")
	}
	if h.LayoutEpoch < 1 {
		return fmt.Errorf("hello with projection has invalid layout epoch: %d", h.LayoutEpoch)
	}
	if h.ScreenRevision < 1 {
		return fmt.Errorf("hello with projection has invalid screen revision: %d", h.ScreenRevision)
	}
	return nil
}

// ValidateResize 校验 resize 消息。
func ValidateResize(r *pb.Resize) error {
	if r.Cols < minCols || r.Cols > maxCols {
		return fmt.Errorf("invalid resize cols: %d", r.Cols)
	}
	if r.Rows < minRows || r.Rows > maxRows {
		return fmt.Errorf("invalid resize rows: %d", r.Rows)
	}
	if r.LeaseId == "" {
		return fmt.Errorf("resize requires layout lease")
	}
	return nil
}

// ValidateInput 校验 input 消息。
func ValidateInput(in *pb.TerminalInput) error {
	if in.LeaseId == "" {
		return fmt.Errorf("input requires layout lease")
	}
	if in.ClientInstanceId == "" {
		return fmt.Errorf("input requires client instance id")
	}
	if in.InputSeq == 0 {
		return fmt.Errorf("input requires positive sequence")
	}
	switch in.Input.(type) {
	case *pb.TerminalInput_Text, *pb.TerminalInput_Key, *pb.TerminalInput_Paste, *pb.TerminalInput_Mouse, *pb.TerminalInput_Focus:
		return nil
	default:
		return fmt.Errorf("unknown input type: %T", in.Input)
	}
}

// ValidateHistoryRequest 校验历史请求。
func ValidateHistoryRequest(r *pb.HistoryRequest) error {
	if r.Limit <= 0 || r.Limit > maxHistoryPage {
		return fmt.Errorf("invalid history limit: %d", r.Limit)
	}
	return nil
}

// ValidateSnapshot 校验 snapshot 资源限制。
func ValidateSnapshot(s *pb.ScreenSnapshot) error {
	if s.Geometry == nil {
		return fmt.Errorf("snapshot missing geometry")
	}
	if s.Geometry.Rows < minRows || s.Geometry.Rows > maxRows {
		return fmt.Errorf("invalid snapshot rows: %d", s.Geometry.Rows)
	}
	if s.Geometry.Cols < minCols || s.Geometry.Cols > maxCols {
		return fmt.Errorf("invalid snapshot cols: %d", s.Geometry.Cols)
	}
	if len(s.HistoryTailIds) > maxSnapshotHistory || len(s.HistoryTailLines) > maxSnapshotHistory {
		return fmt.Errorf("snapshot history too large")
	}
	if s.Title != nil {
		if err := validateString(*s.Title, maxTitleBytes, "title"); err != nil {
			return err
		}
	}
	if s.WorkingDirectory != nil {
		if err := validateString(*s.WorkingDirectory, maxCWDBytes, "cwd"); err != nil {
			return err
		}
	}
	if err := validateStylesLinks(s.Styles, s.Links); err != nil {
		return err
	}
	if s.Layout == nil || len(s.Layout.LineIds) != int(s.Geometry.Rows) {
		return fmt.Errorf("invalid snapshot layout")
	}
	screenIDs, err := validateLineData(s.ScreenLines, int(s.Geometry.Cols))
	if err != nil {
		return err
	}
	historyIDs, err := validateLineData(s.HistoryTailLines, int(s.Geometry.Cols))
	if err != nil {
		return err
	}
	layoutIDs := make(map[uint64]struct{}, len(s.Layout.LineIds))
	for _, id := range s.Layout.LineIds {
		if id == 0 {
			return fmt.Errorf("snapshot layout contains zero line id")
		}
		if _, duplicate := layoutIDs[id]; duplicate {
			return fmt.Errorf("snapshot layout contains duplicate line id: %d", id)
		}
		layoutIDs[id] = struct{}{}
		if _, ok := screenIDs[id]; !ok {
			return fmt.Errorf("snapshot layout line data missing: %d", id)
		}
	}
	var previous uint64
	for index, id := range s.HistoryTailIds {
		if id == 0 || (index > 0 && id <= previous) {
			return fmt.Errorf("invalid snapshot history line id: %d", id)
		}
		if _, ok := historyIDs[id]; !ok {
			return fmt.Errorf("snapshot history line data missing: %d", id)
		}
		previous = id
	}
	return nil
}

// ValidatePatch 校验 patch 资源限制。
func ValidatePatch(p *pb.ScreenPatch) error {
	if p.InstanceId == "" {
		return fmt.Errorf("patch missing instance id")
	}
	if err := validateStylesLinks(p.NewStyles, p.NewLinks); err != nil {
		return err
	}
	if p.BaseRevision < 1 || p.ScreenRevision <= p.BaseRevision {
		return fmt.Errorf("invalid patch revisions")
	}
	if p.Layout != nil {
		seen := make(map[uint64]struct{}, len(p.Layout.LineIds))
		for _, id := range p.Layout.LineIds {
			if id == 0 {
				return fmt.Errorf("patch layout contains zero line id")
			}
			if _, duplicate := seen[id]; duplicate {
				return fmt.Errorf("patch layout contains duplicate line id: %d", id)
			}
			seen[id] = struct{}{}
		}
	}
	if _, err := validateLineData(p.LineUpdates, maxCols); err != nil {
		return err
	}
	var previous uint64
	for index, id := range p.HistoryAppendIds {
		if id == 0 || (index > 0 && id <= previous) {
			return fmt.Errorf("invalid patch history append id: %d", id)
		}
		previous = id
	}
	return nil
}

// ValidateClipboardResponse 限制来自客户端的剪贴板载荷。
func ValidateClipboardResponse(r *pb.ClipboardResponse) error {
	if r.RequestId == "" {
		return fmt.Errorf("clipboard response missing request id")
	}
	if len(r.Data) > 1024*1024 {
		return fmt.Errorf("clipboard response too large: %d", len(r.Data))
	}
	return nil
}

func validateString(s string, max int, name string) error {
	if len(s) > max {
		return fmt.Errorf("%s too long: %d", name, len(s))
	}
	if !utf8.ValidString(s) {
		return fmt.Errorf("%s contains invalid UTF-8", name)
	}
	return nil
}

func validateStylesLinks(styles []*pb.TerminalStyle, links []*pb.Hyperlink) error {
	if len(styles) > 4096 {
		return fmt.Errorf("too many styles: %d", len(styles))
	}
	if len(links) > 4096 {
		return fmt.Errorf("too many links: %d", len(links))
	}
	for _, link := range links {
		if err := validateString(link.Uri, maxURIBytes, "hyperlink uri"); err != nil {
			return err
		}
	}
	return nil
}

func validateLineData(lines []*pb.LineData, maxCols int) (map[uint64]struct{}, error) {
	ids := make(map[uint64]struct{}, len(lines))
	for _, line := range lines {
		if line.LineId == 0 || line.LineVersion == 0 {
			return nil, fmt.Errorf("line id and version must be positive")
		}
		if _, duplicate := ids[line.LineId]; duplicate {
			return nil, fmt.Errorf("duplicate line id: %d", line.LineId)
		}
		ids[line.LineId] = struct{}{}
		if line.Text != "" && len(line.Runs) != 0 {
			return nil, fmt.Errorf("line mixes compact text and runs")
		}
		for _, run := range line.Runs {
			if run.Col < 0 || int(run.Col) >= maxCols {
				return nil, fmt.Errorf("run col out of bounds: %d", run.Col)
			}
			for _, cell := range run.Cells {
				if err := validateString(cell.Text, maxCellTextBytes, "cell text"); err != nil {
					return nil, err
				}
				if cell.Width != 1 && cell.Width != 2 {
					return nil, fmt.Errorf("invalid cell width: %d", cell.Width)
				}
			}
		}
	}
	return ids, nil
}
