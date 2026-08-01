package screenprotocolv3

import (
	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv3"
	"webterm/go-core/internal/terminalengine"
)

// EncodeLineBodyBatchResponse 编码 HTTP 历史正文批量响应（不经 ScreenEnvelope）。
func EncodeLineBodyBatchResponse(
	status pb.LineBodyBatchStatus,
	data terminalengine.LineBodyBatchData,
) ([]byte, error) {
	resp := &pb.LineBodyBatchResponse{
		Status:            status,
		InstanceId:        data.InstanceID,
		LayoutEpoch:       data.LayoutEpoch,
		HistoryGeneration: data.HistoryGeneration,
		RetryAfterMs:      data.RetryAfterMS,
	}
	if status == pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_OK {
		for _, line := range data.Lines {
			resp.Bodies = append(resp.Bodies, encodeLineBodyRecord(line))
		}
		for _, key := range data.MissingKeys {
			resp.MissingKeys = append(resp.MissingKeys, &pb.LineKey{
				LineId: uint64(key.ID), BodyVersion: uint64(key.Version),
			})
		}
		resp.Dictionary = encodeDictionaryForLines(data.Lines, data.Styles, data.Links)
	}
	return proto.Marshal(resp)
}
