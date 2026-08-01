package screenprotocolv3

import (
	"sync/atomic"

	"google.golang.org/protobuf/proto"

	"webterm/go-core/internal/diagnostics"
	pb "webterm/go-core/internal/screenprotocol/generatedv3"
	"webterm/go-core/internal/terminalengine"
)

var lineBodyBatchEncodeMetrics struct {
	encodedBytes atomic.Uint64
	batchCount   atomic.Uint64
	styleCount   atomic.Uint64
	linkCount    atomic.Uint64
}

type LineBodyBatchEncodeMetricsSnapshot struct {
	EncodedBytes uint64
	BatchCount   uint64
	StyleCount   uint64
	LinkCount    uint64
}

func SnapshotLineBodyBatchEncodeMetrics() LineBodyBatchEncodeMetricsSnapshot {
	return LineBodyBatchEncodeMetricsSnapshot{
		EncodedBytes: lineBodyBatchEncodeMetrics.encodedBytes.Load(),
		BatchCount:   lineBodyBatchEncodeMetrics.batchCount.Load(),
		StyleCount:   lineBodyBatchEncodeMetrics.styleCount.Load(),
		LinkCount:    lineBodyBatchEncodeMetrics.linkCount.Load(),
	}
}

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
	encoded, err := proto.Marshal(resp)
	if err == nil {
		lineBodyBatchEncodeMetrics.encodedBytes.Add(uint64(len(encoded)))
		lineBodyBatchEncodeMetrics.batchCount.Add(1)
		lineBodyBatchEncodeMetrics.styleCount.Add(uint64(len(resp.GetDictionary().GetStyles())))
		lineBodyBatchEncodeMetrics.linkCount.Add(uint64(len(resp.GetDictionary().GetLinks())))
		diagnostics.Default.ObserveLineBodyBatchEncoded(
			uint64(len(encoded)), uint64(len(resp.GetDictionary().GetStyles())),
			uint64(len(resp.GetDictionary().GetLinks())))
	}
	return encoded, err
}
