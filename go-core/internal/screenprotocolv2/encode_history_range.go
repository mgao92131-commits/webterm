package screenprotocolv2

import (
	"fmt"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
)

const maxHistoryRangeBytes = 1 << 20

// EncodeHistoryRangeResponse 编码 HTTP 历史范围响应（不经 ScreenEnvelope）。
func EncodeHistoryRangeResponse(
	status pb.HistoryRangeStatus,
	data terminalengine.HistoryRangeData,
) ([]byte, error) {
	resp := &pb.HistoryRangeResponse{
		Status:            status,
		HistoryGeneration: data.HistoryGeneration,
		CurrentExtent:     encodeExtent(data.Extent),
		RetryAfterMs:      data.RetryAfterMS,
	}
	if status == pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_OK {
		resp.Lines = encodeLines(data.Lines)
		resp.Dictionary = encodeDictionaryForLines(data.Lines, data.Styles, data.Links)
	}
	wire, err := proto.Marshal(resp)
	if err != nil {
		return nil, err
	}
	if len(wire) > maxHistoryRangeBytes {
		return nil, fmt.Errorf("history range response exceeds %d bytes", maxHistoryRangeBytes)
	}
	return wire, nil
}
