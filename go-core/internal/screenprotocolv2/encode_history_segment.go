package screenprotocolv2

import (
	"fmt"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/historysegment"
	"webterm/go-core/internal/terminalengine"
)

const maxHistorySegmentBytes = 2 << 20

// EncodeHistorySegmentResponse 编码 HTTP 历史段响应（不经 ScreenEnvelope）。
func EncodeHistorySegmentResponse(
	status pb.HistorySegmentStatus,
	generation uint64,
	seg *historysegment.Segment,
	lines []terminalengine.Line,
	styles []terminalengine.TerminalStyle,
	links []terminalengine.Hyperlink,
	retryAfterMS uint32,
) ([]byte, error) {
	resp := &pb.HistorySegmentResponse{
		Status:            status,
		HistoryGeneration: generation,
		RetryAfterMs:      retryAfterMS,
	}
	if status == pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_OK {
		if seg == nil {
			return nil, fmt.Errorf("OK history segment requires body")
		}
		resp.Segment = &pb.HistorySegment{
			HistoryGeneration: seg.Generation,
			SegmentNumber:     seg.Number,
			FirstSeq:          seg.FirstSeq,
			LastSeq:           seg.LastSeq,
			Lines:             encodeLines(lines),
			Dictionary:        encodeDictionaryForLines(lines, styles, links),
		}
	}
	wire, err := proto.Marshal(resp)
	if err != nil {
		return nil, err
	}
	if len(wire) > maxHistorySegmentBytes {
		return nil, fmt.Errorf("history segment response exceeds %d bytes", maxHistorySegmentBytes)
	}
	return wire, nil
}
